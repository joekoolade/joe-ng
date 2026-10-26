/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import jdk.internal.misc.VM;
import jdk.internal.ref.CleanerFactory;
import sun.invoke.util.Wrapper;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static java.lang.invoke.MethodHandleNatives.Constants.*;
import static java.lang.invoke.MethodHandleStatics.TRACE_METHOD_LINKAGE;
import static java.lang.invoke.MethodHandleStatics.UNSAFE;
import static java.lang.invoke.MethodHandles.Lookup.IMPL_LOOKUP;

/**
 * The JVM interface for the method handles package is all here.
 * This is an interface internal and private to an implementation of JSR 292.
 * <em>This class is not part of the JSR 292 standard.</em>
 * @author jrose
 */
class MethodHandleNatives {

    private MethodHandleNatives() { } // static only

    //--- MemberName support
    //
    // joe-ng overlay, taken from the JDK 26 source (rule 3). Every member below the "Compile-time constants"
    // block is stock. The natives above it are HotSpot's (prims/methodHandles.cpp), and here they are Java
    // written against that C++ source: MemberName resolution is JVMS 5.4.3 over the VM's method registry and
    // the reflection natives it already has, and every reply follows resolve_MemberName / init_method_MemberName
    // / init_field_MemberName flag for flag -- which reference kind comes back is what DirectMethodHandle
    // chooses its LambdaForm by, so an answer that is merely plausible would pick the wrong form silently.
    //
    // WHERE THE VM STATE LIVES. HotSpot injects vmindex into MemberName and vmtarget into ResolvedMethodName.
    // Stock MemberName never reads its own `method` field, so both live on the ResolvedMethodName (overlaid to
    // declare them) and MemberName itself runs stock; its private fields are written here through Unsafe, which
    // is what the VM's C++ does. vmtarget is a REGISTRY INDEX, never a code address: see ResolvedMethodName.
    //
    // ACCESS. A method or constructor resolved on behalf of a caller is access-checked here, as LinkResolver
    // does, with HotSpot's Reflection::verify_member_access -- NOT jdk.internal.reflect.Reflection's Java one,
    // which also demands an accessible DECLARING class and so would refuse StringBuilder.append, a public
    // method declared in package-private AbstractStringBuilder. The check is not redundant with Lookup's: a
    // refusal here is a LinkageError, which MemberName reports as "no such method" -- the message a caller sees.
    //
    // STATED LIMIT: CALLER_SENSITIVE is never set, so a handle to an @CallerSensitive method is not bound to
    // its caller.

    static void init(MemberName self, Object ref) {
        if (ref instanceof Field f) {
            fillField(self, f, false);
            return;
        }
        if (ref instanceof Method m) {
            Class<?> decl = m.getDeclaringClass();
            if ((decl == MethodHandle.class || decl == VarHandle.class) && sigPolyKind(decl, m.getName()) != 0) {
                return;                     // HotSpot does not reify these: MemberName(Method) special-cases a null clazz
            }
            int idx = declaredIndex(decl, m.getName(), descriptorOf(m.getParameterTypes(), m.getReturnType()));
            if (idx < 0) {
                return;                     // clazz stays null -> MemberName(Method) throws LinkageError, as stock
            }
            int acc = accessOf(idx);
            byte kind;
            if (Modifier.isStatic(acc)) {
                kind = REF_invokeStatic;
            } else if (decl.isInterface()) {
                kind = REF_invokeInterface;
            } else {
                kind = REF_invokeVirtual;
            }
            fillMethod(self, decl, decl, idx, acc, kind, false);
            return;
        }
        if (ref instanceof Constructor<?> c) {
            Class<?> decl = c.getDeclaringClass();
            int idx = declaredIndex(decl, "<init>", descriptorOf(c.getParameterTypes(), void.class));
            if (idx < 0) {
                return;
            }
            fillMethod(self, decl, decl, idx, accessOf(idx), REF_invokeSpecial, true);
            return;
        }
        throw new InternalError("unrecognized MemberName init reference: " + ref);
    }

    /** Stub that THROWS (rule 3): every MemberName this VM makes carries its name and type already, so the
     *  stock caller (expandFromVM, only when {@code type == null}) is not expected to be reached. */
    static void expand(MemberName self) {
        throw new InternalError("java.lang.invoke.MethodHandleNatives.expand: not implemented");
    }

    static MemberName resolve(MemberName self, Class<?> caller, int lookupMode,
            boolean speculativeResolve) throws LinkageError, ClassNotFoundException {
        if (self == null) {
            throw new InternalError("mname is null");
        }
        MemberName r;
        try {
            r = resolve0(self, caller);
        } catch (LinkageError e) {
            if (speculativeResolve) {
                return null;                // HotSpot: CLEAR_PENDING_EXCEPTION, then the empty answer
            }
            throw e;
        }
        if (r != null) {
            return r;
        }
        if (speculativeResolve) {
            return null;
        }
        int kinds = UNSAFE.getInt(self, MN_FLAGS) & (MN_IS_METHOD | MN_IS_CONSTRUCTOR | MN_IS_FIELD | MN_IS_TYPE);
        if (kinds == MN_IS_FIELD) {
            throw new NoSuchFieldError("field resolution failed");
        }
        if (kinds == MN_IS_METHOD || kinds == MN_IS_CONSTRUCTOR) {
            throw new NoSuchMethodError("method resolution failed");
        }
        throw new LinkageError("resolution failed");
    }

    //--- Field layout queries parallel to jdk.internal.misc.Unsafe:

    static long objectFieldOffset(MemberName self) {
        return fieldIndex(self, false);
    }

    static long staticFieldOffset(MemberName self) {
        return fieldIndex(self, true);
    }

    /**
     * NULL, where HotSpot answers the declaring class. The base is only ever handed back to Unsafe with the
     * offset above, and joe-ng's Unsafe pairs a static with {@code {null, absolute cell address}} (see
     * Unsafe.staticFieldBase) -- answering the class would make every static access add an object address to an
     * absolute one.
     */
    static Object staticFieldBase(MemberName self) {
        fieldIndex(self, true);
        return null;
    }

    static Object getMemberVMInfo(MemberName self) {
        if (self == null) {
            return null;
        }
        ResolvedMethodName rm = (ResolvedMethodName) UNSAFE.getReference(self, MN_METHOD);
        long vmindex = rm == null ? 0L : rm.vmindex;
        boolean field = (UNSAFE.getInt(self, MN_FLAGS) & MN_IS_FIELD) != 0;
        return new Object[] { Long.valueOf(vmindex), field ? self.getDeclaringClass() : self };
    }

    //--- CallSite support

    /** Tell the JVM that we need to change the target of a CallSite. HotSpot also deoptimizes code that
     *  inlined the old target; nothing here inlines one, so the store is the whole of it. */
    static void setCallSiteTargetNormal(CallSite site, MethodHandle target) {
        UNSAFE.putReference(site, CS_TARGET, target);
    }

    static void setCallSiteTargetVolatile(CallSite site, MethodHandle target) {
        UNSAFE.putReferenceVolatile(site, CS_TARGET, target);
    }

    /** Stub that THROWS (rule 3): only a bootstrap-method upcall for a real invokedynamic or dynamic constant
     *  reaches it, and the VM lowers invokedynamic itself. */
    static void copyOutBootstrapArguments(Class<?> caller, int[] indexInfo,
                                          int start, int end,
                                          Object[] buf, int pos,
                                          boolean resolve,
                                          Object ifNotAvailable) {
        throw new InternalError("java.lang.invoke.MethodHandleNatives.copyOutBootstrapArguments: not implemented");
    }

    /** EMPTY, and provably right: HotSpot's registerNatives binds this class's JNI entry points, and the
     *  natives here are Java methods. */
    private static void registerNatives() {
    }

    // ---- joe-ng: the VM side of the natives above ------------------------------------------------------

    private static final long MN_CLAZZ = UNSAFE.objectFieldOffset(MemberName.class, "clazz");
    private static final long MN_NAME = UNSAFE.objectFieldOffset(MemberName.class, "name");
    private static final long MN_TYPE = UNSAFE.objectFieldOffset(MemberName.class, "type");
    private static final long MN_FLAGS = UNSAFE.objectFieldOffset(MemberName.class, "flags");
    private static final long MN_METHOD = UNSAFE.objectFieldOffset(MemberName.class, "method");
    private static final long CS_TARGET = UNSAFE.objectFieldOffset(CallSite.class, "target");

    /** HotSpot's Method::nonvirtual_vtable_index: the vmindex of a directly-bound method. */
    private static final long NONVIRTUAL_VTABLE_INDEX = -2L;

    /** The recognized-modifier mask MemberName stores (MemberName.RECOGNIZED_MODIFIERS). */
    private static final int MODS = 0xFFFF;

    /** HotSpot's resolve_MemberName. Null is its empty answer; a LinkageError is a pending exception. */
    private static MemberName resolve0(MemberName self, Class<?> caller) throws LinkageError {
        int flags = UNSAFE.getInt(self, MN_FLAGS);
        int refKind = (flags >>> MN_REFERENCE_KIND_SHIFT) & MN_REFERENCE_KIND_MASK;
        if (!refKindIsValid(refKind)) {
            throw new InternalError("obsolete MemberName format");
        }
        Class<?> defc = (Class<?>) UNSAFE.getReference(self, MN_CLAZZ);
        String name = (String) UNSAFE.getReference(self, MN_NAME);
        Object type = UNSAFE.getReference(self, MN_TYPE);
        if (defc == null || name == null || type == null) {
            throw new IllegalArgumentException("nothing to resolve");
        }
        if (defc.isPrimitive()) {
            return null;
        }
        if (defc.isArray()) {
            defc = Object.class;
        }
        if (name.equals("<clinit>")) {
            return null;
        }
        switch (flags & (MN_IS_METHOD | MN_IS_CONSTRUCTOR | MN_IS_FIELD | MN_IS_TYPE)) {
            case MN_IS_METHOD:
                return resolveMethod(self, defc, name, (byte) refKind, caller);
            case MN_IS_CONSTRUCTOR:
                if (!name.equals("<init>")) {
                    return null;
                }
                int idx = declaredIndex(defc, name, self.getMethodDescriptor());
                if (idx < 0 || Modifier.isStatic(accessOf(idx))) {
                    return null;
                }
                checkAccess(caller, defc, defc, accessOf(idx), name);
                fillMethod(self, defc, defc, idx, accessOf(idx), REF_invokeSpecial, true);
                return self;
            case MN_IS_FIELD:
                Field f = findField(defc, name, self.getFieldType());
                if (f == null) {
                    throw new NoSuchFieldError(name);
                }
                fillField(self, f, refKindIsSetter((byte) refKind));
                return self;
            default:
                throw new InternalError("unrecognized MemberName format");
        }
    }

    private static MemberName resolveMethod(MemberName self, Class<?> defc, String name, byte refKind,
            Class<?> caller) {
        if ((defc == MethodHandle.class || defc == VarHandle.class)
                && (refKind == REF_invokeVirtual || refKind == REF_invokeSpecial || refKind == REF_invokeStatic)) {
            int sp = sigPolyKind(defc, name);
            if (sp != 0 && (refKind == REF_invokeStatic) == (sp == SP_STATIC)) {
                return resolveSignaturePolymorphic(self, defc, name, sp);
            }
        }
        String desc = self.getMethodDescriptor();
        Found found;
        if (refKind == REF_invokeInterface || ((refKind == REF_invokeStatic || refKind == REF_invokeSpecial)
                && defc.isInterface())) {
            if (!defc.isInterface()) {
                throw new IncompatibleClassChangeError("Found class " + defc.getName() + ", but interface was expected");
            }
            found = lookupInterfaceMethod(defc, name, desc);
        } else {
            if (defc.isInterface()) {
                throw new IncompatibleClassChangeError("Found interface " + defc.getName() + ", but class was expected");
            }
            found = lookupMethod(defc, name, desc);
        }
        if (found == null) {
            throw new NoSuchMethodError(defc.getName() + "." + name + desc);
        }
        int idx = found.idx;
        Class<?> holder = found.holder;
        int acc = accessOf(idx);
        boolean isStatic = Modifier.isStatic(acc);
        if ((refKind == REF_invokeStatic) != isStatic) {
            throw new IncompatibleClassChangeError((isStatic ? "Expected instance not static method " : "Expected static method ")
                    + defc.getName() + "." + name + desc);
        }
        checkAccess(caller, defc, holder, acc, name);
        // init_method_MemberName: an interface method reached by invokeVirtual (a default or miranda) is a
        // VTABLE call whose MemberName names the resolved CLASS, not the interface; and an interface reference
        // that resolved to one of Object's public methods is a vtable call too (CallInfo's classification).
        Class<?> clazz = (refKind == REF_invokeVirtual && holder.isInterface()) ? defc : holder;
        byte kind = refKind;
        if (refKind == REF_invokeInterface && !holder.isInterface()) {
            kind = REF_invokeVirtual;
        }
        fillMethod(self, clazz, holder, idx, acc, kind, false);
        return self;
    }

    /**
     * MethodHandle.invokeBasic / linkTo* and the VarHandle accessors: HotSpot answers a synthetic intrinsic for
     * the first two (direct, flags NATIVE|SYNTHETIC|FINAL, plus STATIC for linkTo*), and for invoke, invokeExact
     * and every VarHandle accessor a linker WITH AN APPENDIX, which a MemberName cannot carry -- so it throws
     * InternalError("appendix"), and so does this. The MemberName keeps the REQUESTED type. vmtarget is -1: the
     * JIT lowers these call sites itself, and nothing calls through the MemberName.
     */
    private static MemberName resolveSignaturePolymorphic(MemberName self, Class<?> defc, String name, int sp) {
        if (defc == VarHandle.class || name.equals("invoke") || name.equals("invokeExact")) {
            throw new InternalError("appendix");
        }
        int acc = Modifier.NATIVE | Modifier.FINAL | 0x1000;                     // SYNTHETIC
        byte kind = REF_invokeSpecial;
        if (sp == SP_STATIC) {
            acc |= Modifier.STATIC;
            kind = REF_invokeStatic;
        }
        int flags = acc | MN_IS_METHOD | (kind << MN_REFERENCE_KIND_SHIFT);
        UNSAFE.putReference(self, MN_CLAZZ, MethodHandle.class);
        UNSAFE.putInt(self, MN_FLAGS, flags);
        UNSAFE.putReference(self, MN_METHOD, new ResolvedMethodName(MethodHandle.class, -1L, NONVIRTUAL_VTABLE_INDEX));
        return self;
    }

    private static final int SP_VIRTUAL = 1;
    private static final int SP_STATIC = 2;

    /** 0, or which kind of signature-polymorphic method {@code defc.name} is (vmIntrinsics' list). */
    private static int sigPolyKind(Class<?> defc, String name) {
        if (defc == MethodHandle.class) {
            switch (name) {
                case "invoke", "invokeExact", "invokeBasic":
                    return SP_VIRTUAL;
                case "linkToVirtual", "linkToStatic", "linkToSpecial", "linkToInterface", "linkToNative":
                    return SP_STATIC;
                default:
                    return 0;
            }
        }
        if (defc == VarHandle.class) {
            try {
                VarHandle.AccessMode.valueFromMethodName(name);
                return SP_VIRTUAL;
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Fill a method MemberName the way init_method_MemberName does. A virtual or interface reference to a
     * method that can be STATICALLY BOUND -- static, private, final, or declared in a final class (a default
     * method excepted: Method::is_final_method) -- comes back as a DIRECT call, REF_invokeSpecial with the
     * nonvirtual vmindex. DirectMethodHandle depends on that: it is what puts String.length onto a
     * pregenerated invokeSpecial form instead of a virtual one.
     */
    private static void fillMethod(MemberName self, Class<?> clazz, Class<?> holder, int idx, int acc, byte refKind,
            boolean ctor) {
        byte kind = refKind;
        long vmindex = idx;
        if (ctor || Modifier.isStatic(acc) || refKind == REF_invokeSpecial || staticallyBound(holder, acc)) {
            vmindex = NONVIRTUAL_VTABLE_INDEX;
            kind = Modifier.isStatic(acc) ? REF_invokeStatic : REF_invokeSpecial;
        }
        int flags = (acc & MODS) | (ctor ? MN_IS_CONSTRUCTOR : MN_IS_METHOD) | (kind << MN_REFERENCE_KIND_SHIFT);
        UNSAFE.putReference(self, MN_CLAZZ, clazz);
        UNSAFE.putInt(self, MN_FLAGS, flags);
        UNSAFE.putReference(self, MN_METHOD, new ResolvedMethodName(holder, idx, vmindex));
    }

    private static boolean staticallyBound(Class<?> holder, int acc) {
        if (Modifier.isPrivate(acc)) {
            return true;
        }
        boolean defaultMethod = holder.isInterface() && !Modifier.isAbstract(acc);
        if (defaultMethod) {
            return false;
        }
        return Modifier.isFinal(acc) || Modifier.isFinal(holder.getModifiers());
    }

    /** LinkResolver::check_method_accessability: IllegalAccessError unless {@link #memberAccessible}. */
    private static void checkAccess(Class<?> caller, Class<?> resolved, Class<?> member, int acc, String name) {
        if (!memberAccessible(caller, resolved, member, acc)) {
            throw new IllegalAccessError("class " + caller.getName() + " tried to access method "
                    + member.getName() + "." + name);
        }
    }

    /**
     * HotSpot's Reflection::verify_member_access with protected_restriction off, as LinkResolver calls it: no
     * caller (a trusted lookup), the same class, or a public member; a protected member from a subclass; a
     * non-private member from the same runtime package; a private one from a nestmate.
     */
    private static boolean memberAccessible(Class<?> current, Class<?> resolved, Class<?> member, int acc) {
        if (current == null || current == member || Modifier.isPublic(acc)) {
            return true;
        }
        if (Modifier.isProtected(acc) && !current.isInterface() && isSubclassOf(current, member)) {
            if (Modifier.isStatic(acc) || current == resolved || member == resolved
                    || isSubclassOf(current, resolved) || isSubclassOf(resolved, current)) {
                return true;
            }
        }
        if (!Modifier.isPrivate(acc) && current.getClassLoader() == member.getClassLoader()
                && current.getPackageName().equals(member.getPackageName())) {
            return true;
        }
        return Modifier.isPrivate(acc) && current.isNestmateOf(member);
    }

    /** Klass::is_subclass_of: {@code b} is {@code a} or on its superclass chain. */
    private static boolean isSubclassOf(Class<?> a, Class<?> b) {
        for (Class<?> k = a; k != null; k = k.getSuperclass()) {
            if (k == b) {
                return true;
            }
        }
        return false;
    }

    /** Fill a field MemberName the way init_field_MemberName does (TRUSTED_FINAL: fieldDescriptor::is_trusted_final). */
    private static void fillField(MemberName self, Field f, boolean setter) {
        int mods = f.getModifiers() & MODS;
        boolean st = Modifier.isStatic(mods);
        Class<?> decl = f.getDeclaringClass();
        int flags = mods | MN_IS_FIELD | ((st ? REF_getStatic : REF_getField) << MN_REFERENCE_KIND_SHIFT);
        if (Modifier.isFinal(mods) && (st || decl.isHidden() || decl.isRecord())) {
            flags |= MN_TRUSTED_FINAL;
        }
        if (setter) {
            flags += (REF_putField - REF_getField) << MN_REFERENCE_KIND_SHIFT;
        }
        long vmindex = st ? UNSAFE.staticFieldOffset(f) : UNSAFE.objectFieldOffset(f);
        UNSAFE.putReference(self, MN_CLAZZ, decl);
        UNSAFE.putInt(self, MN_FLAGS, flags);
        UNSAFE.putReference(self, MN_METHOD, new ResolvedMethodName(decl, -1L, vmindex));
        UNSAFE.putReference(self, MN_NAME, f.getName());
        UNSAFE.putReference(self, MN_TYPE, f.getType());
    }

    /** find_member_field_offset: the vmindex of a field of the required staticness, else InternalError. */
    private static long fieldIndex(MemberName self, boolean mustBeStatic) {
        if (self == null || UNSAFE.getReference(self, MN_CLAZZ) == null) {
            throw new InternalError("mname not resolved");
        }
        int flags = UNSAFE.getInt(self, MN_FLAGS);
        ResolvedMethodName rm = (ResolvedMethodName) UNSAFE.getReference(self, MN_METHOD);
        if ((flags & MN_IS_FIELD) != 0 && rm != null && Modifier.isStatic(flags) == mustBeStatic) {
            return rm.vmindex;
        }
        throw new InternalError(mustBeStatic ? "static field required" : "non-static field required");
    }

    /** JVMS 5.4.3.2: the class's own fields, then its superinterfaces, then its superclass. */
    private static Field findField(Class<?> c, String name, Class<?> type) {
        try {
            Field f = c.getDeclaredField(name);
            if (f.getType() == type) {
                return f;
            }
        } catch (NoSuchFieldException e) {
            // not declared here: keep looking
        }
        for (Class<?> i : c.getInterfaces()) {
            Field f = findField(i, name, type);
            if (f != null) {
                return f;
            }
        }
        Class<?> s = c.getSuperclass();
        return s == null ? null : findField(s, name, type);
    }

    /** A method lookup's answer: the class that declares it, and its registry index. */
    private static final class Found {
        final Class<?> holder;
        final int idx;

        Found(Class<?> holder, int idx) {
            this.holder = holder;
            this.idx = idx;
        }
    }

    /** JVMS 5.4.3.3: the class and its superclasses, then the maximally-specific superinterface method. */
    private static Found lookupMethod(Class<?> c, String name, String desc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            int idx = declaredIndex(k, name, desc);
            if (idx >= 0) {
                return new Found(k, idx);
            }
        }
        return maximallySpecific(c, name, desc);
    }

    /** JVMS 5.4.3.4: the interface itself, then Object's public instance methods, then its superinterfaces. */
    private static Found lookupInterfaceMethod(Class<?> c, String name, String desc) {
        int idx = declaredIndex(c, name, desc);
        if (idx >= 0) {
            return new Found(c, idx);
        }
        idx = declaredIndex(Object.class, name, desc);
        if (idx >= 0) {
            int acc = accessOf(idx);
            if (Modifier.isPublic(acc) && !Modifier.isStatic(acc)) {
                return new Found(Object.class, idx);
            }
        }
        return maximallySpecific(c, name, desc);
    }

    /**
     * The maximally-specific superinterface method (JVMS 5.4.3.3 step 3): among the non-private, non-static
     * declarations in every superinterface of {@code c} and of its superclasses, prefer one no other candidate
     * overrides (a declaration in a SUBinterface wins), and a default over an abstract one.
     */
    private static Found maximallySpecific(Class<?> c, String name, String desc) {
        Found best = null;
        java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                todo.add(i);
            }
        }
        java.util.HashSet<Class<?>> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> i = todo.poll();
            if (!seen.add(i)) {
                continue;
            }
            for (Class<?> j : i.getInterfaces()) {
                todo.add(j);
            }
            int idx = declaredIndex(i, name, desc);
            if (idx < 0) {
                continue;
            }
            int acc = accessOf(idx);
            if (Modifier.isPrivate(acc) || Modifier.isStatic(acc)) {
                continue;
            }
            if (best == null) {
                best = new Found(i, idx);
                continue;
            }
            Class<?> b = best.holder;
            boolean bAbstract = Modifier.isAbstract(accessOf(best.idx));
            if (b.isAssignableFrom(i) || (bAbstract && !Modifier.isAbstract(acc) && !i.isAssignableFrom(b))) {
                best = new Found(i, idx);
            }
        }
        return best;
    }

    /** The registry index of the method {@code c} itself declares with exactly this name and descriptor
     *  (abstract and native ones included), or -1. Never an inherited one and never a name-only match. */
    private static int declaredIndex(Class<?> c, String name, String desc) {
        return declared0(c, jdk.internal.misc.MetalNames.utf8(name), jdk.internal.misc.MetalNames.utf8(desc));
    }

    /** The access flags of the method at registry index {@code idx}. */
    private static int accessOf(int idx) {
        long[] out = new long[3];                   // {buffer, access, returnChar}
        methodInfo0(idx, new byte[256], out);
        return (int) out[1];
    }

    private static String descriptorOf(Class<?>[] ptypes, Class<?> rtype) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : ptypes) {
            sb.append(p.descriptorString());
        }
        return sb.append(')').append(rtype.descriptorString()).toString();
    }

    // ---- joe-ng: the invokeExact / invoke linker --------------------------------------------------------
    //
    // HotSpot links `invokevirtual MethodHandle.invokeExact:(desc)` by an upcall (linkMethod) that answers an
    // invoker LambdaForm -- invokeExact_MT / invoke_MT -- plus an APPENDIX, the site's MethodType. That
    // invoker's body is `checkExactType(mh, mt); mh.invokeBasic(args)`, or `checkGenericType(mh, mt)
    // .invokeBasic(args)` for invoke. The VM's method-handle trampoline does the same thing in two halves: it
    // calls one of these two methods with the receiver and the site's descriptor, then performs the
    // invokeBasic itself on the handle they return, with the original arguments. The one step left out is
    // checkCustomized, which only respins a hot handle's form for HotSpot's JIT to inline.

    /** Site MethodTypes by descriptor: built once each, and identical across sites (MethodType interns). */
    private static final java.util.concurrent.ConcurrentHashMap<String, MethodType> SITE_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** invokeExact's half: WrongMethodTypeException unless the handle's type IS the site's. */
    static MethodHandle linkExact(MethodHandle mh, String siteDesc) {
        Invokers.checkExactType(mh, siteType(siteDesc));
        return mh;
    }

    /** invoke's half: the handle adapted to the site's type (asType, which is the identity when they match). */
    static MethodHandle linkGeneric(MethodHandle mh, String siteDesc) {
        return Invokers.checkGenericType(mh, siteType(siteDesc));
    }

    private static MethodType siteType(String desc) {
        MethodType mt = SITE_TYPES.get(desc);
        if (mt == null) {
            mt = MethodType.fromMethodDescriptorString(desc, null);
            SITE_TYPES.putIfAbsent(desc, mt);
        }
        return mt;
    }

    /** VM native: see {@link #declaredIndex}. */
    private static native int declared0(Class<?> c, byte[] name, byte[] desc);

    /** VM native (the reflection one): fills param chars + {buf, access, returnChar}; answers the count. */
    private static native int methodInfo0(int rgIndex, byte[] paramChars, long[] out);
    static {
        registerNatives();
    }

    /**
     * Compile-time constants go here. This collection exists not only for
     * reference from clients, but also for ensuring the VM and JDK agree on the
     * values of these constants (see {@link #verifyConstants()}).
     */
    static class Constants {
        Constants() { } // static only

        static final int
            MN_IS_METHOD           = 0x00010000, // method (not constructor)
            MN_IS_CONSTRUCTOR      = 0x00020000, // constructor
            MN_IS_FIELD            = 0x00040000, // field
            MN_IS_TYPE             = 0x00080000, // nested type
            MN_CALLER_SENSITIVE    = 0x00100000, // @CallerSensitive annotation detected
            MN_TRUSTED_FINAL       = 0x00200000, // trusted final field
            MN_HIDDEN_MEMBER       = 0x00400000, // members defined in a hidden class or with @Hidden
            MN_REFERENCE_KIND_SHIFT = 24, // refKind
            MN_REFERENCE_KIND_MASK = 0x0F000000 >> MN_REFERENCE_KIND_SHIFT;

        /**
         * Constant pool reference-kind codes, as used by CONSTANT_MethodHandle CP entries.
         */
        static final byte
            REF_NONE                    = 0,  // null value
            REF_getField                = 1,
            REF_getStatic               = 2,
            REF_putField                = 3,
            REF_putStatic               = 4,
            REF_invokeVirtual           = 5,
            REF_invokeStatic            = 6,
            REF_invokeSpecial           = 7,
            REF_newInvokeSpecial        = 8,
            REF_invokeInterface         = 9,
            REF_LIMIT                  = 10;

        /**
         * Flags for Lookup.ClassOptions
         */
        static final int
            NESTMATE_CLASS            = 0x00000001,
            HIDDEN_CLASS              = 0x00000002,
            STRONG_LOADER_LINK        = 0x00000004,
            ACCESS_VM_ANNOTATIONS     = 0x00000008;

        /**
         * Lookup modes
         */
        static final int
            LM_MODULE        = Lookup.MODULE,
            LM_UNCONDITIONAL = Lookup.UNCONDITIONAL,
            LM_TRUSTED       = -1;

    }

    static boolean refKindIsValid(int refKind) {
        return (refKind > REF_NONE && refKind < REF_LIMIT);
    }
    static boolean refKindIsField(byte refKind) {
        assert(refKindIsValid(refKind));
        return (refKind <= REF_putStatic);
    }
    static boolean refKindIsGetter(byte refKind) {
        assert(refKindIsValid(refKind));
        return (refKind <= REF_getStatic);
    }
    static boolean refKindIsSetter(byte refKind) {
        return refKindIsField(refKind) && !refKindIsGetter(refKind);
    }
    static boolean refKindIsMethod(byte refKind) {
        return !refKindIsField(refKind) && (refKind != REF_newInvokeSpecial);
    }
    static boolean refKindIsConstructor(byte refKind) {
        return (refKind == REF_newInvokeSpecial);
    }
    static boolean refKindHasReceiver(byte refKind) {
        assert(refKindIsValid(refKind));
        return (refKind & 1) != 0;
    }
    static boolean refKindIsStatic(byte refKind) {
        return !refKindHasReceiver(refKind) && (refKind != REF_newInvokeSpecial);
    }
    static boolean refKindDoesDispatch(byte refKind) {
        assert(refKindIsValid(refKind));
        return (refKind == REF_invokeVirtual ||
                refKind == REF_invokeInterface);
    }
    static {
        final int HR_MASK = ((1 << REF_getField) |
                             (1 << REF_putField) |
                             (1 << REF_invokeVirtual) |
                             (1 << REF_invokeSpecial) |
                             (1 << REF_invokeInterface)
                            );
        for (byte refKind = REF_NONE+1; refKind < REF_LIMIT; refKind++) {
            assert(refKindHasReceiver(refKind) == (((1<<refKind) & HR_MASK) != 0)) : refKind;
        }
    }
    static String refKindName(byte refKind) {
        assert(refKindIsValid(refKind));
        return switch (refKind) {
            case REF_getField         -> "getField";
            case REF_getStatic        -> "getStatic";
            case REF_putField         -> "putField";
            case REF_putStatic        -> "putStatic";
            case REF_invokeVirtual    -> "invokeVirtual";
            case REF_invokeStatic     -> "invokeStatic";
            case REF_invokeSpecial    -> "invokeSpecial";
            case REF_newInvokeSpecial -> "newInvokeSpecial";
            case REF_invokeInterface  -> "invokeInterface";
            default -> "REF_???";
        };
    }

    /** HotSpot's PRODUCT answer: 0, with the box left empty (the named-constant table is debug-build only), so
     *  verifyConstants -- reached only from an assert -- ends its loop at once. */
    private static int getNamedCon(int which, Object[] name) {
        return 0;
    }
    static boolean verifyConstants() {
        Object[] box = { null };
        for (int i = 0; ; i++) {
            box[0] = null;
            int vmval = getNamedCon(i, box);
            if (box[0] == null)  break;
            String name = (String) box[0];
            try {
                Field con = Constants.class.getDeclaredField(name);
                int jval = con.getInt(null);
                if (jval == vmval)  continue;
                String err = (name+": JVM has "+vmval+" while Java has "+jval);
                if (name.equals("CONV_OP_LIMIT")) {
                    System.err.println("warning: "+err);
                    continue;
                }
                throw new InternalError(err);
            } catch (NoSuchFieldException | IllegalAccessException ex) {
                String err = (name+": JVM has "+vmval+" which Java does not define");
                // ignore exotic ops the JVM cares about; we just won't issue them
                //System.err.println("warning: "+err);
                continue;
            }
        }
        return true;
    }
    static {
        VM.setJavaLangInvokeInited();
        assert(verifyConstants());
    }

    // Up-calls from the JVM.
    // These must NOT be public.

    /**
     * The JVM is linking an invokedynamic instruction.  Create a reified call site for it.
     */
    static MemberName linkCallSite(Object callerObj,
                                   Object bootstrapMethodObj,
                                   Object nameObj, Object typeObj,
                                   Object staticArguments,
                                   Object[] appendixResult) {
        MethodHandle bootstrapMethod = (MethodHandle)bootstrapMethodObj;
        Class<?> caller = (Class<?>)callerObj;
        String name = nameObj.toString().intern();
        MethodType type = (MethodType)typeObj;
        if (!TRACE_METHOD_LINKAGE)
            return linkCallSiteImpl(caller, bootstrapMethod, name, type,
                                    staticArguments, appendixResult);
        return linkCallSiteTracing(caller, bootstrapMethod, name, type,
                                   staticArguments, appendixResult);
    }
    static MemberName linkCallSiteImpl(Class<?> caller,
                                       MethodHandle bootstrapMethod,
                                       String name, MethodType type,
                                       Object staticArguments,
                                       Object[] appendixResult) {
        CallSite callSite = CallSite.makeSite(bootstrapMethod,
                                              name,
                                              type,
                                              staticArguments,
                                              caller);
        if (TRACE_METHOD_LINKAGE) {
            MethodHandle target = callSite.getTarget();
            System.out.println("linkCallSite target class => " + target.getClass().getName());
            System.out.println("linkCallSite target => " + target.debugString(0));
        }

        if (callSite instanceof ConstantCallSite) {
            appendixResult[0] = callSite.dynamicInvoker();
            return Invokers.linkToTargetMethod(type);
        } else {
            appendixResult[0] = callSite;
            return Invokers.linkToCallSiteMethod(type);
        }
    }
    // Tracing logic:
    static MemberName linkCallSiteTracing(Class<?> caller,
                                          MethodHandle bootstrapMethod,
                                          String name, MethodType type,
                                          Object staticArguments,
                                          Object[] appendixResult) {
        Object bsmReference = bootstrapMethod.internalMemberName();
        if (bsmReference == null)  bsmReference = bootstrapMethod;
        String staticArglist = staticArglistForTrace(staticArguments);
        System.out.println("linkCallSite "+getCallerInfo(caller)+" "+
                           bsmReference+" "+
                           name+type+"/"+staticArglist);
        try {
            MemberName res = linkCallSiteImpl(caller, bootstrapMethod, name, type,
                                              staticArguments, appendixResult);
            System.out.println("linkCallSite linkage => "+res+" + "+appendixResult[0]);
            return res;
        } catch (Throwable ex) {
            ex.printStackTrace(); // print now in case exception is swallowed
            System.out.println("linkCallSite => throw "+ex);
            throw ex;
        }
    }

    /**
     * Return a human-readable description of the caller. Something like
     * "java.base/java.security.Security.<clinit>(Security.java:82)"
     */
    private static String getCallerInfo(Class<?> caller) {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (e.getClassName().equals(caller.getName())) {
                return e.toString();
            }
        }
        // fallback if the caller is somehow missing from the stack.
        return caller.getName();
    }

    // this implements the upcall from the JVM, MethodHandleNatives.linkDynamicConstant:
    static Object linkDynamicConstant(Object callerObj,
                                      Object bootstrapMethodObj,
                                      Object nameObj, Object typeObj,
                                      Object staticArguments) {
        MethodHandle bootstrapMethod = (MethodHandle)bootstrapMethodObj;
        Class<?> caller = (Class<?>)callerObj;
        String name = nameObj.toString().intern();
        Class<?> type = (Class<?>)typeObj;
        if (!TRACE_METHOD_LINKAGE)
            return linkDynamicConstantImpl(caller, bootstrapMethod, name, type, staticArguments);
        return linkDynamicConstantTracing(caller, bootstrapMethod, name, type, staticArguments);
    }

    static Object linkDynamicConstantImpl(Class<?> caller,
                                          MethodHandle bootstrapMethod,
                                          String name, Class<?> type,
                                          Object staticArguments) {
        return ConstantBootstraps.makeConstant(bootstrapMethod, name, type, staticArguments, caller);
    }

    private static String staticArglistForTrace(Object staticArguments) {
        if (staticArguments instanceof Object[] array)
            return "BSA="+java.util.Arrays.asList(array);
        if (staticArguments instanceof int[] array)
            return "BSA@"+java.util.Arrays.toString(array);
        if (staticArguments == null)
            return "BSA0=null";
        return "BSA1="+staticArguments;
    }

    // Tracing logic:
    static Object linkDynamicConstantTracing(Class<?> caller,
                                             MethodHandle bootstrapMethod,
                                             String name, Class<?> type,
                                             Object staticArguments) {
        Object bsmReference = bootstrapMethod.internalMemberName();
        if (bsmReference == null)  bsmReference = bootstrapMethod;
        String staticArglist = staticArglistForTrace(staticArguments);
        System.out.println("linkDynamicConstant "+caller.getName()+" "+
                           bsmReference+" "+
                           name+type+"/"+staticArglist);
        try {
            Object res = linkDynamicConstantImpl(caller, bootstrapMethod, name, type, staticArguments);
            System.out.println("linkDynamicConstantImpl => "+res);
            return res;
        } catch (Throwable ex) {
            ex.printStackTrace(); // print now in case exception is swallowed
            System.out.println("linkDynamicConstant => throw "+ex);
            throw ex;
        }
    }

    /** The JVM is requesting pull-mode bootstrap when it provides
     *  a tuple of the form int[]{ argc, vmindex }.
     *  The BSM is expected to call back to the JVM using the caller
     *  class and vmindex to resolve the static arguments.
     */
    static boolean staticArgumentsPulled(Object staticArguments) {
        return staticArguments instanceof int[];
    }

    /** A BSM runs in pull-mode if and only if its sole arguments
     * are (Lookup, BootstrapCallInfo), or can be converted pairwise
     * to those types, and it is not of variable arity.
     * Excluding error cases, we can just test that the arity is a constant 2.
     *
     * NOTE: This method currently returns false, since pulling is not currently
     * exposed to a BSM. When pull mode is supported the method block will be
     * replaced with currently commented out code.
     */
    static boolean isPullModeBSM(MethodHandle bsm) {
        return false;
//        return bsm.type().parameterCount() == 2 && !bsm.isVarargsCollector();
    }

    /**
     * The JVM wants a pointer to a MethodType.  Oblige it by finding or creating one.
     */
    static MethodType findMethodHandleType(Class<?> rtype, Class<?>[] ptypes) {
        return MethodType.methodType(rtype, ptypes, true);
    }

    /**
     * The JVM wants to link a call site that requires a dynamic type check.
     * Name is a type-checking invoker, invokeExact or invoke.
     * Return a JVM method (MemberName) to handle the invoking.
     * The method assumes the following arguments on the stack:
     * 0: the method handle being invoked
     * 1-N: the arguments to the method handle invocation
     * N+1: an optional, implicitly added argument (typically the given MethodType)
     * <p>
     * The nominal method at such a call site is an instance of
     * a signature-polymorphic method (see @PolymorphicSignature).
     * Such method instances are user-visible entities which are
     * "split" from the generic placeholder method in {@code MethodHandle}.
     * (Note that the placeholder method is not identical with any of
     * its instances.  If invoked reflectively, is guaranteed to throw an
     * {@code UnsupportedOperationException}.)
     * If the signature-polymorphic method instance is ever reified,
     * it appears as a "copy" of the original placeholder
     * (a native final member of {@code MethodHandle}) except
     * that its type descriptor has shape required by the instance,
     * and the method instance is <em>not</em> varargs.
     * The method instance is also marked synthetic, since the
     * method (by definition) does not appear in Java source code.
     * <p>
     * The JVM is allowed to reify this method as instance metadata.
     * For example, {@code invokeBasic} is always reified.
     * But the JVM may instead call {@code linkMethod}.
     * If the result is an * ordered pair of a {@code (method, appendix)},
     * the method gets all the arguments (0..N inclusive)
     * plus the appendix (N+1), and uses the appendix to complete the call.
     * In this way, one reusable method (called a "linker method")
     * can perform the function of any number of polymorphic instance
     * methods.
     * <p>
     * Linker methods are allowed to be weakly typed, with any or
     * all references rewritten to {@code Object} and any primitives
     * (except {@code long}/{@code float}/{@code double})
     * rewritten to {@code int}.
     * A linker method is trusted to return a strongly typed result,
     * according to the specific method type descriptor of the
     * signature-polymorphic instance it is emulating.
     * This can involve (as necessary) a dynamic check using
     * data extracted from the appendix argument.
     * <p>
     * The JVM does not inspect the appendix, other than to pass
     * it verbatim to the linker method at every call.
     * This means that the JDK runtime has wide latitude
     * for choosing the shape of each linker method and its
     * corresponding appendix.
     * Linker methods should be generated from {@code LambdaForm}s
     * so that they do not become visible on stack traces.
     * <p>
     * The {@code linkMethod} call is free to omit the appendix
     * (returning null) and instead emulate the required function
     * completely in the linker method.
     * As a corner case, if N==255, no appendix is possible.
     * In this case, the method returned must be custom-generated to
     * perform any needed type checking.
     * <p>
     * If the JVM does not reify a method at a call site, but instead
     * calls {@code linkMethod}, the corresponding call represented
     * in the bytecodes may mention a valid method which is not
     * representable with a {@code MemberName}.
     * Therefore, use cases for {@code linkMethod} tend to correspond to
     * special cases in reflective code such as {@code findVirtual}
     * or {@code revealDirect}.
     */
    static MemberName linkMethod(Class<?> callerClass, int refKind,
                                 Class<?> defc, String name, Object type,
                                 Object[] appendixResult) {
        if (!TRACE_METHOD_LINKAGE)
            return linkMethodImpl(callerClass, refKind, defc, name, type, appendixResult);
        return linkMethodTracing(callerClass, refKind, defc, name, type, appendixResult);
    }
    static MemberName linkMethodImpl(Class<?> callerClass, int refKind,
                                     Class<?> defc, String name, Object type,
                                     Object[] appendixResult) {
        try {
            if (refKind == REF_invokeVirtual) {
                if (defc == MethodHandle.class) {
                    return Invokers.methodHandleInvokeLinkerMethod(
                            name, fixMethodType(callerClass, type), appendixResult);
                } else if (defc == VarHandle.class) {
                    return varHandleOperationLinkerMethod(
                            name, fixMethodType(callerClass, type), appendixResult);
                }
            }
        } catch (Error e) {
            // Pass through an Error, including say StackOverflowError or
            // OutOfMemoryError
            throw e;
        } catch (Throwable ex) {
            // Wrap anything else in LinkageError
            throw new LinkageError(ex.getMessage(), ex);
        }
        throw new LinkageError("no such method "+defc.getName()+"."+name+type);
    }
    private static MethodType fixMethodType(Class<?> callerClass, Object type) {
        if (type instanceof MethodType mt)
            return mt;
        else
            return MethodType.fromDescriptor((String)type, callerClass.getClassLoader());
    }
    // Tracing logic:
    static MemberName linkMethodTracing(Class<?> callerClass, int refKind,
                                        Class<?> defc, String name, Object type,
                                        Object[] appendixResult) {
        System.out.println("linkMethod "+defc.getName()+"."+
                           name+type+"/"+Integer.toHexString(refKind));
        try {
            MemberName res = linkMethodImpl(callerClass, refKind, defc, name, type, appendixResult);
            System.out.println("linkMethod => "+res+" + "+appendixResult[0]);
            return res;
        } catch (Throwable ex) {
            System.out.println("linkMethod => throw "+ex);
            throw ex;
        }
    }

    /**
     * Obtain the method to link to the VarHandle operation.
     * This method is located here and not in Invokers to avoid
     * initializing that and other classes early on in VM bootup.
     */
    private static MemberName varHandleOperationLinkerMethod(String name,
                                                             MethodType mtype,
                                                             Object[] appendixResult) {
        // Get the signature method type
        final MethodType sigType = mtype.basicType();

        // Get the access kind from the method name
        VarHandle.AccessMode ak;
        try {
            ak = VarHandle.AccessMode.valueFromMethodName(name);
        } catch (IllegalArgumentException e) {
            throw MethodHandleStatics.newInternalError(e);
        }

        // Create the appendix descriptor constant
        VarHandle.AccessDescriptor ad = new VarHandle.AccessDescriptor(mtype, ak.at.ordinal(), ak.ordinal());
        appendixResult[0] = ad;

        if (MethodHandleStatics.VAR_HANDLE_GUARDS) {
            // If not polymorphic in the return type, such as the compareAndSet
            // methods that return boolean
            Class<?> guardReturnType = sigType.returnType();
            if (ak.at.isMonomorphicInReturnType) {
                if (ak.at.returnType != mtype.returnType()) {
                    // The caller contains a different return type than that
                    // defined by the method
                    throw newNoSuchMethodErrorOnVarHandle(name, mtype);
                }
                // Adjust the return type of the signature method type
                guardReturnType = ak.at.returnType;
            }

            // Get the guard method type for linking
            final Class<?>[] guardParams = new Class<?>[sigType.parameterCount() + 2];
            // VarHandle at start
            guardParams[0] = VarHandle.class;
            for (int i = 0; i < sigType.parameterCount(); i++) {
                guardParams[i + 1] = sigType.parameterType(i);
            }
            // Access descriptor at end
            guardParams[guardParams.length - 1] = VarHandle.AccessDescriptor.class;
            MethodType guardType = MethodType.methodType(guardReturnType, guardParams, true);

            MemberName linker = new MemberName(
                    VarHandleGuards.class, getVarHandleGuardMethodName(guardType),
                    guardType, REF_invokeStatic);

            linker = MemberName.getFactory().resolveOrNull(REF_invokeStatic, linker,
                                                           VarHandleGuards.class, LM_TRUSTED);
            if (linker != null) {
                return linker;
            }
            // Fall back to lambda form linkage if guard method is not available
            // TODO Optionally log fallback ?
        }
        return Invokers.varHandleInvokeLinkerMethod(mtype);
    }
    static String getVarHandleGuardMethodName(MethodType guardType) {
        String prefix = "guard_";
        StringBuilder sb = new StringBuilder(prefix.length() + guardType.parameterCount());

        sb.append(prefix);
        for (int i = 1; i < guardType.parameterCount() - 1; i++) {
            Class<?> pt = guardType.parameterType(i);
            sb.append(getCharType(pt));
        }
        sb.append('_').append(getCharType(guardType.returnType()));
        return sb.toString();
    }
    static char getCharType(Class<?> pt) {
        return Wrapper.forBasicType(pt).basicTypeChar();
    }
    static NoSuchMethodError newNoSuchMethodErrorOnVarHandle(String name, MethodType mtype) {
        return new NoSuchMethodError("VarHandle." + name + mtype);
    }

    /**
     * The JVM is resolving a CONSTANT_MethodHandle CP entry.  And it wants our help.
     * It will make an up-call to this method.  (Do not change the name or signature.)
     * The type argument is a Class for field requests and a MethodType for non-fields.
     * <p>
     * Recent versions of the JVM may also pass a resolved MemberName for the type.
     * In that case, the name is ignored and may be null.
     */
    static MethodHandle linkMethodHandleConstant(Class<?> callerClass, int refKind,
                                                 Class<?> defc, String name, Object type) {
        try {
            Lookup lookup = IMPL_LOOKUP.in(callerClass);
            assert(refKindIsValid(refKind));
            return lookup.linkMethodHandleConstant((byte) refKind, defc, name, type);
        } catch (ReflectiveOperationException ex) {
            throw mapLookupExceptionToError(ex);
        }
    }

    /**
     * Map a reflective exception to a linkage error.
     */
    static LinkageError mapLookupExceptionToError(ReflectiveOperationException ex) {
        LinkageError err;
        if (ex instanceof IllegalAccessException) {
            Throwable cause = ex.getCause();
            if (cause instanceof AbstractMethodError ame) {
                return ame;
            } else {
                err = new IllegalAccessError(ex.getMessage());
            }
        } else if (ex instanceof NoSuchMethodException) {
            err = new NoSuchMethodError(ex.getMessage());
        } else if (ex instanceof NoSuchFieldException) {
            err = new NoSuchFieldError(ex.getMessage());
        } else {
            err = new IncompatibleClassChangeError();
        }
        return initCauseFrom(err, ex);
    }

    /**
     * Use best possible cause for err.initCause(), substituting the
     * cause for err itself if the cause has the same (or better) type.
     */
    static <E extends Error> E initCauseFrom(E err, Exception ex) {
        Throwable th = ex.getCause();
        @SuppressWarnings("unchecked")
        final Class<E> Eclass = (Class<E>) err.getClass();
        if (Eclass.isInstance(th))
           return Eclass.cast(th);
        err.initCause(th == null ? ex : th);
        return err;
    }

    /**
     * Is this method a caller-sensitive method?
     * I.e., does it call Reflection.getCallerClass or a similar method
     * to ask about the identity of its caller?
     */
    static boolean isCallerSensitive(MemberName mem) {
        if (!mem.isInvocable())  return false;  // fields are not caller sensitive

        return mem.isCallerSensitive() || canBeCalledVirtual(mem);
    }

    static boolean canBeCalledVirtual(MemberName mem) {
        assert(mem.isInvocable());
        return mem.getName().equals("getContextClassLoader") && canBeCalledVirtual(mem, java.lang.Thread.class);
    }

    static boolean canBeCalledVirtual(MemberName symbolicRef, Class<?> definingClass) {
        Class<?> symbolicRefClass = symbolicRef.getDeclaringClass();
        if (symbolicRefClass == definingClass)  return true;
        if (symbolicRef.isStatic() || symbolicRef.isPrivate())  return false;
        return (definingClass.isAssignableFrom(symbolicRefClass) ||  // Msym overrides Mdef
                symbolicRefClass.isInterface());                     // Mdef implements Msym
    }
}
