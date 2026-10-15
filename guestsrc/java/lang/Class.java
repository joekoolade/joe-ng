/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-30
 */
package java.lang;

import magic.Magic;

/**
 * Bare-metal {@code java/lang/Class} mirror. The VM materialises one Class instance per loaded VM Type (on an
 * {@code ldc} class-literal or {@code Object.getClass()}, in {@code Loader.classMirror}) and stores the raw
 * Type-node pointer in {@link #typeAddr}; the mirror is cached per Type, so {@code X.class} and
 * {@code obj.getClass()} return the SAME identity — which is what stock code compares (e.g.
 * {@code Arrays.copyOf}'s {@code newType == Object[].class}). This override keeps the huge stock
 * {@code java.lang.Class} (and its reflection/CDS closure) out of the image; instance methods
 * ({@code getName}/{@code getComponentType}/{@code isInstance}/...) are added on demand as the code that runs
 * on metal reaches them. The VM allocates the object directly (bypassing this ctor).
 */
public final class Class<T> implements java.io.Serializable, java.lang.reflect.GenericDeclaration,
                                       java.lang.reflect.Type, java.lang.reflect.AnnotatedElement
{
    private long typeAddr;      // the VM Type node this Class mirrors (set by the VM at materialisation)

    /**
     * Per-Class map of {@link ClassValue} entries, declared exactly as JDK 26 does
     * ({@code transient ClassValue.ClassValueMap classValueMap;}, Class.java:3717).
     *
     * <p>{@code ClassValue.get} reads and writes this field on the Class it is keyed by; without it the
     * access resolved NOWHERE and the VM aliased it to SLOT 0 -- i.e. it read and wrote {@code typeAddr},
     * the Type pointer every Class native dereferences. The loader said so
     * ({@code UNRESOLVED FIELD (aliases slot 0): java/lang/Class.classValueMap}) and the corruption surfaced
     * as an NPE inside the VM's own dispatch resolver, a long way from here.
     *
     * <p>ADDING IT REQUIRED WIDENING THE MIRROR: {@code Loader.classMirror} allocates Class objects itself,
     * and its size was header(16) + one field. A declaration without that change writes past the object.
     */
    transient ClassValue.ClassValueMap classValueMap;

    private Class()
    {
    }

    /**
     * Load (if needed) and return the {@code Class} for the binary name {@code name} (dots), initializing it.
     * On metal this pulls the class + its dependency closure into the live program on demand (the VM's
     * incremental loader), then runs its {@code <clinit>}. Throws {@code ClassNotFoundException} if the class
     * is not embedded or the name is not a valid binary name.
     */
    public static Class<?> forName(String name) throws ClassNotFoundException
    {
        return forName(name, true, null);
    }

    /**
     * As {@link #forName(String)}, with an explicit {@code initialize} flag and class loader. On metal the
     * class is always initialized when loaded (the loader runs {@code <clinit>} as part of the batch); the
     * {@code initialize} flag and {@code loader} are accepted for source compatibility.
     */
    public static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException
    {
        Class<?> c = (name == null) ? null : forName0(jdk.internal.misc.MetalNames.utf8(name));
        if (c == null)
        {
            throw new ClassNotFoundException(name);
        }
        return c;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.forName}): raw binary-name bytes -> Class mirror, or null. */
    private static native Class forName0(byte[] name);

    /**
     * Assertions are off on metal (no -ea). Stock {@code <clinit>}s read this into their {@code $assertionsDisabled}
     * flag (e.g. {@code java.util.regex.Pattern.<clinit>} does {@code ldc X.class; desiredAssertionStatus()}); with
     * this it can run to completion and initialise its static nodes instead of being skipped.
     */
    public boolean desiredAssertionStatus()
    {
        return false;
    }

    /** The class's binary name with dots (M4), built by the VM from the loader registry's name bytes. */
    /**
     * The single unnamed module (joe-ng has no module layer -- see {@link Module}). Stock code calls this on
     * ordinary paths, e.g. JUnit's {@code ModuleUtils.getModuleVersion}, which then short-circuits on
     * {@code isNamed()}.
     */
    /**
     * Always null: joe-ng has no {@code Package} objects -- the boot image is a flat class directory with no
     * package-level metadata (no sealing, no spec/impl title or version, no manifest attributes).
     *
     * <p>Null is a SUPPORTED answer, not a fudge. The JDK contract is "null if no Package object was created
     * by the class loader", and stock callers handle it: JUnit's {@code PackageUtils.getAttribute} wraps this
     * in {@code Optional.ofNullable(...)} precisely so an absent package yields an empty Optional. Returning
     * a fabricated Package with blank attributes would be the lie.
     */
    public Package getPackage()
    {
        return null;
    }

    public Module getModule()
    {
        return Module.UNNAMED;
    }


    /**
     * Stock's: {@code "interface " + name}, a primitive's bare name, otherwise {@code "class " + name}. It was
     * undeclared, so every mirror printed as {@code java.lang.Class@<hash>} -- {@code Arrays.toString} of a
     * {@code getParameterTypes()} array among them (ReflectLookupProbe).
     */
    public String toString()
    {
        String kind = isInterface() ? "interface " : (isPrimitive() ? "" : "class ");
        return kind.concat(getName());
    }
    public String getName()
    {
        return getName0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classNameOf}): mirror -> a fresh name String. */
    private static native String getName0(Class c);

    /**
     * The Java language modifiers of this class/interface ({@code public}/{@code private}/{@code abstract}/...).
     * For a nested class these come from the enclosing class's {@code InnerClasses} attribute; the VM-internal
     * {@code ACC_SUPER} bit is stripped by the VM.
     */
    public int getModifiers()
    {
        return (int) classModifiers0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classModifiers}): mirror -> class access flags. Returns
     *  {@code long} (not {@code int}) deliberately: a 1-arg {@code int}-returning native mis-compiled in the JIT
     *  (register-clobber corruption); the {@code (J)J} shape matches the working {@code getName0}/{@code superclass0}. */
    private static native long classModifiers0(Class c);

    /** True if this Class represents an interface (the {@code ACC_INTERFACE} bit). */
    public boolean isInterface()
    {
        return (classModifiers0(this) & 0x0200) != 0;
    }

    /**
     * Every method this class declares, in VM registry order. Enumerated from the method registry (which is
     * exactly what the VM knows about -- each method of a registered class is there, compiled or as a deferral
     * stub) rather than from the classfile.
     *
     * <p>With {@code Method.isAnnotationPresent} this is what makes annotation-driven discovery possible: find
     * the methods carrying {@code @Test} instead of hand-listing their names.
     */
    public java.lang.reflect.Method[] getDeclaredMethods()
    {
        int n = (int) declaredMethodCount0(this);
        java.lang.reflect.Method[] out = new java.lang.reflect.Method[n];
        int i = 0;
        int k = 0;
        while (i < n)
        {
            String nm = declaredMethodAt0(this, i);
            String ds = declaredMethodDescAt0(this, i);
            try
            {
                // BY NAME AND DESCRIPTOR: a class may declare two methods of the same name, and resolving by name
                // alone hands back the same one for both -- so an overloaded class enumerated to duplicates and
                // the other overload was unreachable. (A stock @ParameterizedTest is exactly that shape: the test
                // and its same-named @MethodSource factory.)
                out[k] = java.lang.reflect.Method.resolve(this, nm, ds);   // resolves + compiles on demand
                k += 1;
            }
            catch (NoSuchMethodException e)
            {
                // a declared method the VM cannot resolve (native without a helper) -- skip it
            }
            i += 1;
        }
        if (k == n)
        {
            return out;
        }
        java.lang.reflect.Method[] trimmed = new java.lang.reflect.Method[k];
        int j = 0;
        while (j < k)
        {
            trimmed[j] = out[j];
            j += 1;
        }
        return trimmed;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodAt}): the n-th declared method's NAME. */
    /**
     * Every PUBLIC method of this class: its own, those inherited from superclasses, and those reachable
     * through its interfaces (including defaults). Deduplicated by name AND descriptor.
     *
     * <p>The key has to be built HERE rather than from a {@link java.lang.reflect.Method}, because a Method
     * keeps only the first character of each parameter type -- enough to marshal a call, not enough to tell
     * two overloads apart. The enumeration natives hand back the full descriptor, so the dedupe happens
     * before the Method is ever constructed.
     *
     * <p>A subclass method wins over the superclass method it overrides simply by being seen first, which is
     * what walking the chain most-derived-first gives.
     *
     * <p>Constructors and {@code <clinit>} are excluded, as stock does -- the VM's method enumeration is the
     * registry's view and lists them, so they are filtered by name.
     */
    public java.lang.reflect.Method[] getMethods()
    {
        java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
        java.util.ArrayList<java.lang.reflect.Method> out =
                new java.util.ArrayList<java.lang.reflect.Method>();
        Class<?> c = this;
        while (c != null)
        {
            collectPublicMethods(c, seen, out);
            c = c.getSuperclass();
        }
        collectInterfaceMethods(this, seen, out);
        java.lang.reflect.Method[] arr = new java.lang.reflect.Method[out.size()];
        int i = 0;
        while (i < arr.length)
        {
            arr[i] = out.get(i);
            i += 1;
        }
        return arr;
    }

    /** Append {@code c}'s own public methods that no more-derived class has already contributed. */
    private static void collectPublicMethods(Class<?> c, java.util.ArrayList<String> seen,
            java.util.ArrayList<java.lang.reflect.Method> out)
    {
        int n = (int) declaredMethodCount0(c);
        int i = 0;
        while (i < n)
        {
            String nm = declaredMethodAt0(c, i);
            String ds = declaredMethodDescAt0(c, i);
            if (nm != null && ds != null && nm.length() > 0 && nm.charAt(0) != '<')
            {
                String key = nm + ds;
                if (!seen.contains(key))
                {
                    try
                    {
                        java.lang.reflect.Method m = java.lang.reflect.Method.resolve(c, nm, ds);
                        if (java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                        {
                            seen.add(key);
                            out.add(m);
                        }
                    }
                    catch (NoSuchMethodException e)
                    {
                        // a declared method the VM cannot resolve (a native with no helper) -- skip it, as
                        // getDeclaredMethods does
                    }
                }
            }
            i += 1;
        }
    }

    /** Append the public methods of {@code c}'s interfaces, transitively -- where DEFAULT methods live. */
    private static void collectInterfaceMethods(Class<?> c, java.util.ArrayList<String> seen,
            java.util.ArrayList<java.lang.reflect.Method> out)
    {
        Class<?> k = c;
        while (k != null)
        {
            Class<?>[] ifs = k.getInterfaces();
            int i = 0;
            while (i < ifs.length)
            {
                collectPublicMethods(ifs[i], seen, out);
                collectInterfaceMethods(ifs[i], seen, out);   // super-interfaces
                i += 1;
            }
            k = k.getSuperclass();
        }
    }

    private static native String declaredMethodAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodDescAt}): the n-th method's DESCRIPTOR. */
    private static native String declaredMethodDescAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodCount}): how many methods it declares.
     *  {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long declaredMethodCount0(Class<?> c);

    /** True if this Class is an array type — the VM tags array Types, so this is a tag test on the mirror. */
    public boolean isArray()
    {
        return isArray0(this) != 0L;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.isArrayClass}): mirror -> 1 if its Type is an array
     *  Type. {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long isArray0(Class c);

    /** The element type of an array class (from the array Type's element slot), else null. Feeds
     *  {@code Array.newInstance(a.getClass().getComponentType(), n)} (TimSort/Arrays.copyOf/toArray) the right
     *  element Type so the created array is typed {@code [L<component>;} (and {@code instanceof T[]} matches). */
    public Class<?> getComponentType()
    {
        return getComponentType0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.componentTypeOf}): array Class -> element-type mirror. */
    private static native Class<?> getComponentType0(Class c);

    /**
     * Stock java.base calls this from the wrapper classes' initializers -- {@code Integer.TYPE =
     * getPrimitiveClass("int")}, and likewise {@code Void} -- so a name-winning {@code Class} overlay that
     * omits it makes every one of those {@code <clinit>}s trap. That is exactly what happened: the first run
     * died in {@code java/lang/Void.<clinit>}.
     *
     * <p>Stock declares it {@code native}; here the name→descriptor mapping is ordinary Java and only the
     * mirror lookup is a native, which keeps the native's signature a plain {@code (J)J}.
     */
    static Class<?> getPrimitiveClass(String name)
    {
        int c = 0;
        if (name.equals("int"))          { c = 0x49; }
        else if (name.equals("long"))    { c = 0x4A; }
        else if (name.equals("double"))  { c = 0x44; }
        else if (name.equals("float"))   { c = 0x46; }
        else if (name.equals("short"))   { c = 0x53; }
        else if (name.equals("byte"))    { c = 0x42; }
        else if (name.equals("char"))    { c = 0x43; }
        else if (name.equals("boolean")) { c = 0x5A; }
        else if (name.equals("void"))    { c = 0x56; }
        return primitiveClass0(c);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.primClassOf}): descriptor char -> primitive mirror. */
    private static native Class<?> primitiveClass0(long descChar);

    /** True if this Class is a primitive type ({@code int.class}), i.e. its Type carries the primitive tag. */
    public boolean isPrimitive()
    {
        return isPrimitive0(this) != 0L;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.isPrimClass}): mirror -> 1 if its Type is a primitive
     *  Type. {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long isPrimitive0(Class c);

    /** True if this class was synthesised by the compiler ({@code ACC_SYNTHETIC}). */
    public boolean isSynthetic()
    {
        return (classModifiers0(this) & 0x1000) != 0;
    }

    /** True if this Class represents an enum type (the {@code ACC_ENUM} bit; nested enums carry it in the
     *  enclosing class's {@code InnerClasses} entry, which {@code getModifiers} already reads). */
    public boolean isEnum()
    {
        return (getModifiers() & 0x4000) != 0;
    }

    /**
     * Is this a record class? Answered by its SUPERCLASS being {@code java.lang.Record}, which is the check
     * stock makes first (Class.java:3386) before its intrinsified fast path.
     *
     * <p>The FINAL-modifier and {@code isRecord0()} halves of stock's test are deliberately not reproduced:
     * the superclass check is decisive on its own here, because {@code java.lang.Record} is abstract and the
     * compiler is the only thing that may extend it -- JLS 8.10 forbids a class declaring
     * {@code extends Record} directly. Stock's own comment says as much: "this superclass and final modifier
     * check is not strictly necessary".
     *
     * <p>Reached from {@code java/io/ObjectStreamClass.<init>}, which is how serialization describes any
     * class at all; without it that constructor resolved nowhere and surfaced as a trap with an EMPTY callee
     * and {@code TRAPWIRE index=-1} -- the signature of a LATE-RESOLUTION failure rather than a denial.
     */
    /**
     * This class's field descriptor: {@code "I"}, {@code "[I"}, {@code "Ljava/lang/String;"}.
     *
     * <p>Derived from {@link #getName()}, whose three shapes are exactly the three cases here -- a primitive
     * spells its own name, an array is already in descriptor form bar the separators, and everything else
     * wraps. Reached from {@code java/io/ObjectStreamField.<init>}, i.e. from describing ANY class for
     * serialization.
     */
    public String descriptorString()
    {
        if (isPrimitive())
        {
            String n = getName();
            if (n.equals("int"))     { return "I"; }
            if (n.equals("long"))    { return "J"; }
            if (n.equals("boolean")) { return "Z"; }
            if (n.equals("byte"))    { return "B"; }
            if (n.equals("char"))    { return "C"; }
            if (n.equals("short"))   { return "S"; }
            if (n.equals("float"))   { return "F"; }
            if (n.equals("double"))  { return "D"; }
            return "V";                             // void
        }
        // An array's name is ALREADY a descriptor apart from the package separator ("[Ljava.lang.String;").
        if (isArray())
        {
            return getName().replace('.', '/');
        }
        return "L" + getName().replace('.', '/') + ";";
    }

    public boolean isRecord()
    {
        Class<?> sup = getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getName());
    }

    /**
     * The enum constants of this enum type (in declaration order), or {@code null} if this is not an enum.
     * Implemented via the enum's compiler-synthesised {@code values()} (reached through M2 reflection) — the
     * same array the language exposes — rather than the stock {@code getEnumConstantsShared} cache. Not cloned
     * (single-threaded, read-only use), so the caller must not mutate the returned array.
     */
    /**
     * name -> constant, for {@code Enum.valueOf(Class, String)}.
     *
     * <p>Package-private and returning a raw {@code Map}, matching stock, because {@code Enum.valueOf} calls it
     * directly and both live in {@code java.lang}. Built from {@link #getEnumConstants()}, which reaches the
     * enum's own {@code values()} -- so the constants here are the SAME objects the rest of the VM holds, and
     * {@code valueOf(E.class, "X") == E.X} as identity, not merely as equals.
     *
     * <p>NOT CACHED, unlike stock's volatile field. Caching would mean a new field on {@code Class}, whose
     * mirrors the VM allocates itself; rebuilding costs one reflective {@code values()} call on a path
     * ({@code Enum.valueOf}) that is not hot, and avoids putting VM-allocated objects and guest field layout in
     * the same argument.
     */
    java.util.Map enumConstantDirectory()
    {
        java.util.HashMap m = new java.util.HashMap();
        Object[] constants = getEnumConstants();
        if (constants == null)
        {
            return m;                                   // not an enum, or values() unreachable: empty, not null
        }
        for (int i = 0; i < constants.length; i++)
        {
            Object c = constants[i];
            if (c != null)
            {
                m.put(((Enum) c).name(), c);
            }
        }
        return m;
    }

    public Object[] getEnumConstants()
    {
        if (!isEnum())
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method values = getDeclaredMethod("values");
            return (Object[]) values.invoke(null);
        }
        catch (Exception e)
        {
            // NOT A SILENT NULL. Returning null here is stock's answer for a class that is not an enum --
            // but isEnum() has already said this one IS, so reaching this catch means the reflective route
            // to values() failed, and every caller downstream reads that as "this enum has no constants":
            // Enum.valueOf then throws "No enum constant X.Y" for a constant that plainly exists.
            //
            // That silence hid a real VM defect for the life of the lazy-compile arc -- rememberLazyBody
            // registered a celled static with access_flags 0, which reads as package-private, so invoking
            // a public values() from java.lang was refused with IllegalAccessException. The exception was
            // caught here and discarded, so the failure surfaced as a wrong ANSWER in Enum.valueOf rather
            // than as the access error it was. It says what it caught now.
            System.err.println("ENUM CONSTANTS UNREADABLE: " + getName() + " is an enum, but values() threw "
                               + e.getClass().getName() + " -- Enum.valueOf on it will report no such constant");
            return null;
        }
    }

    /** The package name (the binary name up to, but excluding, the last '.'), or "" for the default package. */
    public String getPackageName()
    {
        String n = getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(0, dot);
    }

    /**
     * Stock's {@code getSimpleName0}, uncached (a mirror carries no reflection data): an array's is its
     * component's plus {@code []}, a nested class's is the simple name its {@code InnerClasses} entry records
     * ({@code ""} for an anonymous class), and a top-level class's is its binary name past the package.
     */
    public String getSimpleName()
    {
        if (isArray())
        {
            return getComponentType().getSimpleName().concat("[]");
        }
        String simpleName = getSimpleBinaryName();
        if (simpleName == null)
        {
            simpleName = getName();
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
        }
        return simpleName;
    }

    /**
     * Stock's {@code getCanonicalName0}, uncached: null for a local or anonymous class, an array of one, or a
     * member of one. Stock also answers null for a HIDDEN class; this VM defines none through
     * {@code Lookup.defineHiddenClass}, so that arm is absent rather than guessed.
     */
    public String getCanonicalName()
    {
        if (isArray())
        {
            String canonicalName = getComponentType().getCanonicalName();
            if (canonicalName != null)
            {
                return canonicalName.concat("[]");
            }
            return null;
        }
        if (isLocalOrAnonymousClass())
        {
            return null;
        }
        Class<?> enclosingClass = getEnclosingClass();
        if (enclosingClass == null)
        {
            return getName();
        }
        String enclosingName = enclosingClass.getCanonicalName();
        if (enclosingName == null)
        {
            return null;
        }
        String simpleName = getSimpleName();
        return new StringBuilder(enclosingName.length() + simpleName.length() + 1)
                .append(enclosingName)
                .append('.')
                .append(simpleName)
                .toString();
    }

    /** True if {@code obj} is non-null and assignable to this type (the {@code instanceof} walk). */
    public boolean isInstance(Object obj)
    {
        return isInstance0(obj, typeAddr);
    }

    /** VM native (maps straight onto the JIT's {@code VM.instanceOf(JJ)I} helper: obj in x0, Type in x1). */
    private static native boolean isInstance0(Object obj, long type);

    /**
     * True if {@code other}'s type equals this type or has it on its superclass chain — a pure-Java walk of
     * the Type nodes ({@code superType} at Type+8) via the {@code Magic.load64} intrinsic. Interface
     * assignability (itables) is not consulted; extend when reached code needs it.
     */
    /**
     * Is a value of {@code other} assignable to this type?
     *
     * <p>THIS USED TO WALK {@code Type.superType} AND NOTHING ELSE -- the SUPERCLASS chain -- so every
     * INTERFACE answer was false: {@code Collection.class.isAssignableFrom(List.class)} said no, and so did
     * {@code List.class.isAssignableFrom(ArrayList.class)}. The demo suite never caught it because the arm it
     * asserts, {@code Number.isAssignableFrom(Integer)}, is a class-chain question that always worked.
     *
     * <p>picocli's {@code isMultiValue()} IS this call: {@code Collection.class.isAssignableFrom(
     * field.getType())}. Answering false meant a {@code List<ClassSelector>} option was treated as
     * single-valued, so a bare ClassSelector was stored into the List field -- and JUnit's
     * {@code getExplicitSelectors} then called {@code addAll} on it, whose first act is {@code toArray()}.
     *
     * <p>It asks the VM now. {@code VM.typeAssignable} has always been the real rule: itable directories for
     * interfaces, array covariance, and an O(1) display check for class targets. Keeping a second, weaker
     * copy of that rule here bought nothing.
     */
    public boolean isAssignableFrom(Class other)
    {
        if (other == null)
        {
            throw new NullPointerException();
        }
        return assignable0(other.typeAddr, typeAddr);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.assignable} -> {@code VM.typeAssignable}). */
    private static native boolean assignable0(long fromType, long targetType);

    /**
     * The cheap half of the reflection surface {@code make overlaycheck} listed as REFERENCED but dropped.
     * Each of these is answerable from what the mirror already knows -- the Type chain, the modifiers, and the
     * name -- so leaving them undeclared bought nothing and cost a DENYLIST TRAP on the day one is reached.
     *
     * <p>The half NOT added here is the half that needs machinery joe-ng does not have:
     * {@code getAnnotation}/{@code getAnnotations} need a live annotation instance (a Proxy runtime),
     * {@code getProtectionDomain} needs {@code java/security} (denylisted) and the generic-signature methods
     * need a signature parser. Those stay in the backlog rather than being answered wrongly. ({@code getResource*}
     * was in this list; it is declared below {@link #getClassLoader} now.)
     */
    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> clazz)
    {
        if (!clazz.isAssignableFrom(this))
        {
            throw new ClassCastException(getName() + " is not a subclass of " + clazz.getName());
        }
        return (Class<? extends U>) this;
    }

    /** Stock semantics: null casts cleanly; anything else must be an instance. */
    @SuppressWarnings("unchecked")
    public T cast(Object obj)
    {
        if (obj != null && !isInstance(obj))
        {
            throw new ClassCastException("Cannot cast " + obj.getClass().getName() + " to " + getName());
        }
        return (T) obj;
    }

    /**
     * The annotation INSTANCE carried by this class, or null. Same machinery as {@link
     * java.lang.reflect.Method#getAnnotation} -- the returned object implements the annotation interface, so
     * calling an element method on it is an ordinary interface dispatch.
     *
     * <p>THE BOUND IS LOAD-BEARING. {@code <T extends Annotation>} erases the return to
     * {@code Ljava/lang/annotation/Annotation;}, which is the descriptor stock callers reference. Declared as a
     * plain {@code <T>} it erases to {@code Ljava/lang/Object;} -- a DIFFERENT METHOD, which resolves nowhere
     * and traps. That is exactly what happened: the first cut of this compiled, ran green in a probe that
     * called it directly, and still failed for JUnit's launcher.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(this, annoDescriptorOf(anno));
    }

    /**
     * The annotation DECLARED DIRECTLY on this class, or null.
     *
     * <p>In stock this differs from {@link #getAnnotation} by {@code @Inherited} alone: getAnnotation also
     * finds an {@code @Inherited} annotation on a SUPERCLASS. The native here reads THIS class's own
     * {@code RuntimeVisibleAnnotations} and nothing else, so the two coincide -- which makes THIS method
     * exact and {@code getAnnotation} the one that diverges. Stated rather than hidden: an {@code @Inherited}
     * annotation on a superclass is found by neither, and nothing reached so far asks for one.
     *
     * <p>THE BOUND IS LOAD-BEARING, for the reason spelled out on {@link #getAnnotation}: it erases the
     * return to {@code Ljava/lang/annotation/Annotation;}, which is the descriptor stock callers reference.
     * JUnit reaches this through {@code AnnotatedElement}, whose method it is -- and this class does not
     * declare that interface (recorded in the overlay baseline), so the call arrives by LATE dispatch against
     * the receiver's class and only resolves if the name AND descriptor match exactly.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getDeclaredAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(this, annoDescriptorOf(anno));
    }

    /** Takes a wildcard, as stock does, so it cannot call the BOUNDED {@code getAnnotation} directly. */
    public boolean isAnnotationPresent(Class<? extends java.lang.annotation.Annotation> anno)
    {
        return anno != null && annoGet0(this, annoDescriptorOf(anno)) != null;
    }

    /**
     * Every annotation DECLARED DIRECTLY on this class. Never null; empty when there are none.
     *
     * <p>This is what JUnit's {@code AnnotationUtils.findAnnotation} walks to find META-ANNOTATIONS -- an
     * annotation carried by another annotation -- so an empty answer would not fail, it would silently report
     * that nothing is annotated.
     *
     * <p>A FRESH array each call, as stock specifies ("the caller of this method is free to modify the
     * returned array"). An annotation whose interface is not loaded is omitted and named on the console by
     * the VM, rather than appearing as a null element.
     */
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        java.lang.annotation.Annotation[] a = annoAll0(this);
        return a == null ? new java.lang.annotation.Annotation[0] : a;
    }

    /**
     * As {@link #getDeclaredAnnotations}. In stock these differ by {@code @Inherited} alone -- getAnnotations
     * also reports an {@code @Inherited} annotation carried by a SUPERCLASS -- and this VM does not implement
     * that, so the two coincide. Stated rather than hidden, and the same divergence {@code getAnnotation}
     * already carries.
     */
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAnnoGet}): mirror + descriptor -> instance. */
    private static native Object annoGet0(Class c, byte[] descriptor);

    /**
     * The classes and interfaces declared as MEMBERS of this class -- public, protected, package and private
     * alike -- excluding inherited ones. Never null; empty when there are none.
     *
     * <p>Read from the classfile's {@code InnerClasses} attribute, not from the binary name. The nesting
     * PREDICATES above work from the name because they ask "is THIS class a member", which {@code Outer$Inner}
     * answers; this asks the reverse, "which classes are members of this one", and no name can answer that.
     *
     * <p>LOCAL AND ANONYMOUS CLASSES ARE EXCLUDED, as stock excludes them: JVMS 4.7.6 requires a zero
     * {@code outer_class_info_index} for both, which is exactly the field the VM filters on.
     *
     * <p>Getting these mirrors does NOT initialize the classes -- obtaining a Class is not an active use
     * (JVMS 5.5). A member class this VM cannot load is omitted and named on the console rather than returned
     * as a null element.
     */
    public Class<?>[] getDeclaredClasses()
    {
        Class<?>[] a = declaredClasses0(this);
        return a == null ? new Class<?>[0] : a;
    }

    /**
     * The PUBLIC member classes of this class, including those inherited from superclasses and
     * superinterfaces. Never null.
     *
     * <p>Landed beside {@link #getDeclaredClasses} deliberately: a member a name-winning overlay does not
     * declare CEASES TO EXIST, and shipping one half of a pair is how that trap has cost this project a boot
     * ten times over. Nothing reached so far calls it -- {@code ReflectionUtils} uses the declared form -- so
     * it is here to be present and correct rather than because something waits on it.
     *
     * <p>Walks the superclass chain and the interfaces, most-derived first, and de-duplicates by name: a
     * class and its superclass may both report the same inherited member.
     */
    public Class<?>[] getClasses()
    {
        java.util.ArrayList<Class<?>> out = new java.util.ArrayList<Class<?>>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Class<?> c = this;
        while (c != null)
        {
            collectPublicClasses(c, out, seen);
            c = c.getSuperclass();
        }
        collectInterfaceClasses(this, out, seen);
        return out.toArray(new Class<?>[0]);
    }

    /**
     * Public member classes of {@code c}'s interfaces, TRANSITIVELY -- a super-interface's members are
     * inherited too, so a walk of the DIRECT interfaces alone misses them. Same shape as
     * {@link #collectInterfaceMethods}, deliberately: two walks over the same relation that disagreed about
     * transitivity would be the kind of half-correct member this overlay keeps getting bitten by.
     */
    private static void collectInterfaceClasses(Class<?> c, java.util.ArrayList<Class<?>> out,
            java.util.HashSet<String> seen)
    {
        Class<?> k = c;
        while (k != null)
        {
            Class<?>[] ifs = k.getInterfaces();
            for (int i = 0; i < ifs.length; i++)
            {
                collectPublicClasses(ifs[i], out, seen);
                collectInterfaceClasses(ifs[i], out, seen);   // super-interfaces
            }
            k = k.getSuperclass();
        }
    }

    /** Adds {@code c}'s public member classes that have not been seen; a member of an interface is public. */
    private static void collectPublicClasses(Class<?> c, java.util.ArrayList<Class<?>> out,
            java.util.HashSet<String> seen)
    {
        Class<?>[] d = c.getDeclaredClasses();
        for (int i = 0; i < d.length; i++)
        {
            if (java.lang.reflect.Modifier.isPublic(d[i].getModifiers()) && seen.add(d[i].getName()))
            {
                out.add(d[i]);
            }
        }
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classDeclClasses}): mirror -> Class[]. */
    private static native Class<?>[] declaredClasses0(Class c);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classAnnoAll}): mirror -> Annotation[]. */
    private static native java.lang.annotation.Annotation[] annoAll0(Class c);

    /**
     * The interfaces this class DIRECTLY declares, in declaration order; empty when it declares none.
     *
     * <p>Declared, not the transitive closure -- which is what stock returns, and what JUnit's
     * {@code findAnnotation} needs, since it recurses into each interface itself.
     */
    public Class<?>[] getInterfaces()
    {
        Class<?>[] a = interfaces0(this);
        return a == null ? new Class<?>[0] : a;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classIfaces}): mirror -> Class[]. */
    private static native Class<?>[] interfaces0(Class c);

    /**
     * Stock's: the {@code ParameterizedType}s this class's {@code Signature} attribute declares for its direct
     * superinterfaces, or -- for a class with no signature -- the erased {@link #getInterfaces}.
     *
     * <p>THIS USED TO ANSWER THE ERASURES ALWAYS, because the VM read no generic signatures. Stock
     * {@code java.util.HashMap} calls it whenever a bin treeifies ({@code comparableClassFor}): with the
     * erasure its {@code t instanceof ParameterizedType} test always failed, so a tree of {@code Integer} keys
     * was ordered by HashMap's identity-hash fallback rather than by {@code compareTo}. Every lookup still
     * succeeded, so nothing observable broke -- which is how a silent wrong answer survives. Stock's answer
     * now, and stock's ordering with it.
     */
    public java.lang.reflect.Type[] getGenericInterfaces()
    {
        sun.reflect.generics.repository.ClassRepository info = getGenericInfo();
        if (info == null)
        {
            Class<?>[] a = getInterfaces();
            java.lang.reflect.Type[] out = new java.lang.reflect.Type[a.length];
            for (int i = 0; i < a.length; i++)
            {
                out[i] = a[i];
            }
            return out;
        }
        return info.getSuperInterfaces();
    }

    /**
     * Stock's: the superclass as this class's {@code Signature} declares it -- a {@code ParameterizedType} for
     * {@code extends ArrayList<String>} -- or the erased {@link #getSuperclass} when there is no signature;
     * null for an interface, a primitive and {@code Object}.
     */
    public java.lang.reflect.Type getGenericSuperclass()
    {
        sun.reflect.generics.repository.ClassRepository info = getGenericInfo();
        if (info == null)
        {
            return getSuperclass();
        }
        // Historical irregularity: Generic signature marks interfaces with superclass = Object but this API
        // returns null for interfaces
        if (isInterface())
        {
            return null;
        }
        return info.getSuperclass();
    }

    /**
     * Stock's: modifiers, sealing, kind, name and the type parameters with their bounds -- e.g.
     * {@code public class java.util.HashMap<K,V>}, {@code public abstract interface java.lang.Comparable<T>}.
     */
    public String toGenericString()
    {
        if (isPrimitive())
        {
            return toString();
        }
        else
        {
            StringBuilder sb = new StringBuilder();
            Class<?> component = this;
            int arrayDepth = 0;

            if (isArray())
            {
                do
                {
                    arrayDepth++;
                    component = component.getComponentType();
                }
                while (component.isArray());
                sb.append(component.getName());
            }
            else
            {
                // Class modifiers are a superset of interface modifiers
                int modifiers = getModifiers() & java.lang.reflect.Modifier.classModifiers();
                if (modifiers != 0)
                {
                    sb.append(java.lang.reflect.Modifier.toString(modifiers));
                    sb.append(' ');
                }

                // A class cannot be strictfp and sealed/non-sealed so it is sufficient to check for
                // sealed-ness after all modifiers are printed.
                addSealingInfo(modifiers, sb);

                if (isAnnotation())
                {
                    sb.append('@');
                }
                if (isInterface())
                {
                    // Note: all annotation interfaces are interfaces
                    sb.append("interface");
                }
                else
                {
                    if (isEnum())
                    {
                        sb.append("enum");
                    }
                    else if (isRecord())
                    {
                        sb.append("record");
                    }
                    else
                    {
                        sb.append("class");
                    }
                }
                sb.append(' ');
                sb.append(getName());
            }

            java.lang.reflect.TypeVariable<?>[] typeparms = component.getTypeParameters();
            if (typeparms.length > 0)
            {
                sb.append(java.util.Arrays.stream(typeparms)
                          .map(Class::typeVarBounds)
                          .collect(java.util.stream.Collectors.joining(",", "<", ">")));
            }

            if (arrayDepth > 0)
            {
                sb.append("[]".repeat(arrayDepth));
            }

            return sb.toString();
        }
    }

    static String typeVarBounds(java.lang.reflect.TypeVariable<?> typeVar)
    {
        java.lang.reflect.Type[] bounds = typeVar.getBounds();
        if (bounds.length == 1 && bounds[0].equals(Object.class))
        {
            return typeVar.getName();
        }
        else
        {
            return typeVar.getName() + " extends "
                + java.util.Arrays.stream(bounds)
                .map(java.lang.reflect.Type::getTypeName)
                .collect(java.util.stream.Collectors.joining(" & "));
        }
    }

    private void addSealingInfo(int modifiers, StringBuilder sb)
    {
        // A class can be final XOR sealed XOR non-sealed.
        if (java.lang.reflect.Modifier.isFinal(modifiers))
        {
            return; // no-op
        }
        else
        {
            if (isSealed())
            {
                sb.append("sealed ");
                return;
            }
            else
            {
                // Check for sealed ancestor, which implies this class is non-sealed.
                if (hasSealedAncestor(this))
                {
                    sb.append("non-sealed ");
                }
            }
        }
    }

    private boolean hasSealedAncestor(Class<?> clazz)
    {
        // From JLS 8.1.1.2: a direct subclass of a sealed class must declare itself final, sealed or
        // non-sealed, so checking the direct superclass and superinterfaces is enough.
        var superclass = clazz.getSuperclass();
        if (superclass != null && superclass.isSealed())
        {
            return true;
        }
        for (var superinterface : clazz.getInterfaces())
        {
            if (superinterface.isSealed())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Stock's definition -- {@code getPermittedSubclasses() != null} for a non-array, non-primitive class --
     * answered from the attribute that makes it non-null: {@code PermittedSubclasses} present. The permitted
     * classes themselves ({@code getPermittedSubclasses}) are not built here.
     */
    public boolean isSealed()
    {
        if (isArray() || isPrimitive())
        {
            return false;
        }
        return sealed0(this) != 0L;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classSealed}): 1 if PermittedSubclasses is present. */
    private static native long sealed0(Class<?> c);

    /** Stock's: the type variables this class declares, in order; empty for a class with no signature. */
    @SuppressWarnings("unchecked")
    public java.lang.reflect.TypeVariable<Class<T>>[] getTypeParameters()
    {
        sun.reflect.generics.repository.ClassRepository info = getGenericInfo();
        if (info != null)
        {
            return (java.lang.reflect.TypeVariable<Class<T>>[]) info.getTypeParameters();
        }
        return (java.lang.reflect.TypeVariable<Class<T>>[]) new java.lang.reflect.TypeVariable<?>[0];
    }

    /**
     * Stock's generic-info accessor, WITHOUT stock's per-mirror cache: a mirror is VM-allocated, and a new
     * instance field would have to be laid out by the VM as well. Each call parses the signature afresh, so
     * two calls answer EQUAL type variables rather than identical ones -- {@code TypeVariableImpl.equals} is
     * by declaration and name, which is the contract stock documents.
     */
    private sun.reflect.generics.repository.ClassRepository getGenericInfo()
    {
        String signature = genericSignature0(this);
        if (signature == null)
        {
            return null;
        }
        return sun.reflect.generics.repository.ClassRepository.make(signature, getFactory());
    }

    /** Stock's: a factory resolving names through this class's scope (its own, then its enclosing ones). */
    private sun.reflect.generics.factory.GenericsFactory getFactory()
    {
        return sun.reflect.generics.factory.CoreReflectionFactory.make(this,
                sun.reflect.generics.scope.ClassScope.make(this));
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classSignature}): the Signature attribute, or null. */
    private static native String genericSignature0(Class<?> c);

    /**
     * Stock's: the method a local or anonymous class is declared in, or null (no {@code EnclosingMethod}
     * attribute, an initializer, or a constructor). Stock parses the descriptor into classes with
     * {@code BytecodeDescriptor} and compares parameter and return types; this compares each candidate's
     * DESCRIPTOR with the attribute's instead, which is the same test without loading every named type.
     */
    public java.lang.reflect.Method getEnclosingMethod()
    {
        EnclosingMethodInfo enclosingInfo = getEnclosingMethodInfo();
        if (enclosingInfo == null)
        {
            return null;
        }
        if (!enclosingInfo.isMethod())
        {
            return null;
        }
        String descriptor = enclosingInfo.getDescriptor();
        for (java.lang.reflect.Method m : enclosingInfo.getEnclosingClass().getDeclaredMethods())
        {
            if (m.getName().equals(enclosingInfo.getName())
                    && descriptorOf(m.getParameterTypes(), m.getReturnType()).equals(descriptor))
            {
                return m;
            }
        }
        throw new InternalError("Enclosing method not found");
    }

    /** As {@link #getEnclosingMethod}, for a class declared inside a constructor. */
    public java.lang.reflect.Constructor<?> getEnclosingConstructor()
    {
        EnclosingMethodInfo enclosingInfo = getEnclosingMethodInfo();
        if (enclosingInfo == null)
        {
            return null;
        }
        if (!enclosingInfo.isConstructor())
        {
            return null;
        }
        String descriptor = enclosingInfo.getDescriptor();
        for (java.lang.reflect.Constructor<?> c : enclosingInfo.getEnclosingClass().getDeclaredConstructors())
        {
            if (descriptorOf(c.getParameterTypes(), void.class).equals(descriptor))
            {
                return c;
            }
        }
        throw new InternalError("Enclosing constructor not found");
    }

    /** The method descriptor {@code (params)ret} a classfile would record for these types. */
    private static String descriptorOf(Class<?>[] params, Class<?> ret)
    {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params)
        {
            sb.append(p.descriptorString());
        }
        return sb.append(')').append(ret.descriptorString()).toString();
    }

    /**
     * Stock's {@code getEnclosingMethod0}, built in Java from the {@code EnclosingMethod} attribute: null when
     * the class has none, else {enclosing class, method name or null, descriptor or null}. The enclosing
     * class is resolved by name through {@link #forName}, which on this VM also INITIALIZES it -- stock does
     * not. A local or anonymous class runs inside its enclosing class's code, so that class is normally
     * initialized already.
     */
    private Object[] getEnclosingMethod0()
    {
        String cn = enclosingPart0(this, 0L);
        if (cn == null)
        {
            return null;
        }
        return new Object[] { classNamed(cn), enclosingPart0(this, 1L), enclosingPart0(this, 2L) };
    }

    private EnclosingMethodInfo getEnclosingMethodInfo()
    {
        Object[] enclosingInfo = getEnclosingMethod0();
        if (enclosingInfo == null)
        {
            return null;
        }
        return new EnclosingMethodInfo(enclosingInfo);
    }

    /** Stock's. */
    private static final class EnclosingMethodInfo
    {
        private final Class<?> enclosingClass;
        private final String name;
        private final String descriptor;

        static void validate(Object[] enclosingInfo)
        {
            if (enclosingInfo.length != 3)
            {
                throw new InternalError("Malformed enclosing method information");
            }
            try
            {
                Class<?> enclosingClass = (Class<?>) enclosingInfo[0];
                assert (enclosingClass != null);
                String name = (String) enclosingInfo[1];
                String descriptor = (String) enclosingInfo[2];
                assert ((name != null && descriptor != null) || name == descriptor);
            }
            catch (ClassCastException cce)
            {
                throw new InternalError("Invalid type in enclosing method information", cce);
            }
        }

        EnclosingMethodInfo(Object[] enclosingInfo)
        {
            validate(enclosingInfo);
            this.enclosingClass = (Class<?>) enclosingInfo[0];
            this.name = (String) enclosingInfo[1];
            this.descriptor = (String) enclosingInfo[2];
        }

        boolean isPartial()
        {
            return enclosingClass == null || name == null || descriptor == null;
        }

        boolean isConstructor()
        {
            return !isPartial() && "<init>".equals(name);
        }

        boolean isMethod()
        {
            return !isPartial() && !isConstructor() && !"<clinit>".equals(name);
        }

        Class<?> getEnclosingClass()
        {
            return enclosingClass;
        }

        String getName()
        {
            return name;
        }

        String getDescriptor()
        {
            if (descriptor.isEmpty() || descriptor.charAt(0) != '(')
            {
                throw new java.lang.reflect.GenericSignatureFormatError("Bad method signature: " + descriptor);
            }
            return descriptor;
        }
    }

    /** An internal name from a classfile ({@code a/b/C}) -> its mirror; a dangling one is a linkage error. */
    private static Class<?> classNamed(String internalName)
    {
        try
        {
            return forName(internalName.replace('/', '.'), false, null);
        }
        catch (ClassNotFoundException e)
        {
            NoClassDefFoundError err = new NoClassDefFoundError(internalName);
            err.initCause(e);
            throw err;
        }
    }

    /**
     * VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classEnclosingPart}): part 0 the EnclosingMethod
     * attribute's class (internal name; null when the attribute is absent), 1 the method name, 2 its descriptor.
     */
    private static native String enclosingPart0(Class<?> c, long part);

    /**
     * VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classInnerPart}): from this class's own
     * InnerClasses entry, part 0 the outer class (internal name) and part 1 the simple name; null when absent.
     */
    private static native String innerPart0(Class<?> c, long part);

    /** {@code com.x.Foo} -> the bytes of {@code Lcom/x/Foo;} -- the form the classfile stores. */
    private static byte[] annoDescriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        for (int i = 0; i < n.length(); i++)
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
        }
        out[out.length - 1] = (byte) ';';
        return out;
    }

    /**
     * {@code newInstance()} -- the no-arg construction stock deprecated in favour of
     * {@code getDeclaredConstructor().newInstance()}, which is exactly what it delegates to here.
     *
     * <p>Stock declares the checked {@code InstantiationException}/{@code IllegalAccessException} and this
     * keeps them, so a caller's existing catch blocks still compile and still run: picocli's
     * {@code DefaultFactory.create} catches around it and falls back, and swallowing the failure here would
     * turn its fallback into dead code.
     */
    @SuppressWarnings("unchecked")
    public T newInstance() throws InstantiationException, IllegalAccessException
    {
        try
        {
            return (T) getDeclaredConstructor().newInstance();
        }
        catch (NoSuchMethodException e)
        {
            throw new InstantiationException(getName() + " has no no-arg constructor");
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            throw new InstantiationException(getName() + " constructor threw");
        }
    }

    /** For a non-array, non-primitive class this is {@link #getName()}; arrays report the source-style form. */
    public String getTypeName()
    {
        if (isArray())
        {
            Class<?> c = getComponentType();
            return c == null ? getName() : c.getTypeName() + "[]";
        }
        return getName();
    }

    /** Null: joe-ng loads every class through one VM loader, which is the bootstrap loader's own answer. */
    public ClassLoader getClassLoader()
    {
        return null;
    }

    /** Stock's unchecked accessor, which java.base calls in place of {@link #getClassLoader}; the same answer. */
    ClassLoader getClassLoader0()
    {
        return null;
    }

    /**
     * Stock's unnamed-module arm: resolve {@code name} against this class's package, then ask the loader -- which,
     * {@link #getClassLoader} being null, is the system loader, exactly as stock does for a bootstrap class.
     * The bytes come out of the classpath jar ({@link ClassLoader#getResourceAsStream}); every class here is in
     * the unnamed module, so stock's named-module encapsulation check has nothing to apply to.
     */
    public java.io.InputStream getResourceAsStream(String name)
    {
        name = resolveName(name);
        return ClassLoader.getSystemResourceAsStream(name);
    }

    /**
     * Stock's unnamed-module arm, as {@link #getResourceAsStream}. It answers what {@link ClassLoader#getResource}
     * answers: null, and a console report when the jar DOES hold the resource, because this VM cannot build a
     * {@link java.net.URL} for it -- the stream form is the one that serves bytes.
     */
    public java.net.URL getResource(String name)
    {
        name = resolveName(name);
        return ClassLoader.getSystemResource(name);
    }

    /** Stock's: a name without a leading '/' is relative to this class's package; a leading '/' is dropped. */
    private String resolveName(String name)
    {
        if (!name.startsWith("/"))
        {
            String baseName = getPackageName();
            if (!baseName.isEmpty())
            {
                int len = baseName.length() + 1 + name.length();
                StringBuilder sb = new StringBuilder(len);
                name = sb.append(baseName.replace('.', '/'))
                    .append('/')
                    .append(name)
                    .toString();
            }
        }
        else
        {
            name = name.substring(1);
        }
        return name;
    }

    public boolean isAnnotation()
    {
        return (getModifiers() & 0x2000) != 0;          // ACC_ANNOTATION
    }

    /** Stock's: a member class is not local or anonymous, and names an outer class in InnerClasses. */
    public boolean isMemberClass()
    {
        return !isLocalOrAnonymousClass() && getDeclaringClass0() != null;
    }

    /** Stock's: local or anonymous, with no simple name recorded. */
    public boolean isAnonymousClass()
    {
        return !isArray() && isLocalOrAnonymousClass() && getSimpleBinaryName0() == null;
    }

    /** Stock's. */
    public boolean isLocalClass()
    {
        return isLocalOrAnonymousClass() && (isArray() || getSimpleBinaryName0() != null);
    }

    /**
     * Stock's: from the {@code EnclosingMethod} attribute for a local or anonymous class, else from the
     * class's {@code InnerClasses} entry. This used to strip the binary name at its last '$' and look that up,
     * so a top-level class whose own name contains '$' read as nested in another.
     */
    public Class<?> getEnclosingClass()
    {
        EnclosingMethodInfo enclosingInfo = getEnclosingMethodInfo();
        Class<?> enclosingCandidate;
        if (enclosingInfo == null)
        {
            enclosingCandidate = getDeclaringClass0();
        }
        else
        {
            Class<?> enclosingClass = enclosingInfo.getEnclosingClass();
            if (enclosingClass == this || enclosingClass == null)
            {
                throw new InternalError("Malformed enclosing method information");
            }
            enclosingCandidate = enclosingClass;
        }
        return enclosingCandidate;
    }

    /**
     * Stock's: the class this one is a MEMBER of, or null. Stock generic reflection depends on it:
     * {@code ParameterizedTypeImpl} takes a member type's OWNER from it.
     */
    public Class<?> getDeclaringClass()
    {
        return getDeclaringClass0();
    }

    /** HotSpot's {@code getDeclaringClass0}: the outer class named by this class's own InnerClasses entry. */
    private Class<?> getDeclaringClass0()
    {
        String outer = innerPart0(this, 0L);
        return outer == null ? null : classNamed(outer);
    }

    /** HotSpot's {@code getSimpleBinaryName0}: the simple name in this class's own InnerClasses entry. */
    private String getSimpleBinaryName0()
    {
        return innerPart0(this, 1L);
    }

    /** Stock's. */
    private String getSimpleBinaryName()
    {
        if (isTopLevelClass())
        {
            return null;
        }
        String name = getSimpleBinaryName0();
        if (name == null)
        {
            return "";
        }
        return name;
    }

    /** Stock's. */
    private boolean isTopLevelClass()
    {
        return !isLocalOrAnonymousClass() && getDeclaringClass0() == null;
    }

    /** Stock's: JVMS 4.7.7 -- a class has an EnclosingMethod attribute iff it is local or anonymous. */
    private boolean isLocalOrAnonymousClass()
    {
        return hasEnclosingMethodInfo();
    }

    /** Stock's. */
    private boolean hasEnclosingMethodInfo()
    {
        Object[] enclosingInfo = getEnclosingMethod0();
        if (enclosingInfo != null)
        {
            EnclosingMethodInfo.validate(enclosingInfo);
            return true;
        }
        return false;
    }

    /** Stock's: the array class whose component type is this one; {@code void} has none. */
    public Class<?> arrayType()
    {
        try
        {
            return java.lang.reflect.Array.newInstance(this, 0).getClass();
        }
        catch (IllegalArgumentException iae)
        {
            throw new UnsupportedOperationException(iae);
        }
    }

    /** The superclass's mirror (cached per Type, so {@code getSuperclass() == Super.class}), or null. */
    public Class getSuperclass()
    {
        return superclass0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.superclassOf}): super Type -> its (cached) mirror. */
    private static native Class superclass0(Class c);

    /**
     * Declared instance/static field named {@code name} (any access), or throws {@code NoSuchFieldException}.
     * Modifiers + type descriptor come from the loader re-walking this class's classfile ({@code fieldMeta0}).
     */
    /**
     * Every field this class DECLARES -- public or not, instance or static -- in classfile order.
     *
     * <p>Enumerated by index and resolved by NAME, which is sound here in a way the method equivalent is not:
     * a class cannot declare two fields of the same name, so there is no descriptor ambiguity to guard against
     * (that guard exists in {@link #getDeclaredMethods} because overloads DO collide, and resolving by name
     * alone handed back the same overload twice).
     *
     * <p>Inherited fields are excluded, as stock requires -- {@link #getFields} is the one that walks up.
     *
     * <p><b>STATIC fields are omitted, and this DIVERGES from stock.</b> A {@code Field} here reads through an
     * INSTANCE offset ({@code Field.addr} is {@code addrOf(obj) + fieldOffset}), and joe-ng's field registry
     * holds instance fields only -- so a static would have to be handed back as an object whose {@code get()}
     * computes a meaningless address. Returning nothing is the lesser wrong: a caller iterating fields sees
     * fewer than it should, which is visible, rather than reading a plausible number from the wrong memory,
     * which is not. Closing it properly means giving Field a static-cell mode, not widening this walk.
     */
    public java.lang.reflect.Field[] getDeclaredFields()
    {
        int n = (int) declaredFieldCount0(this);
        java.lang.reflect.Field[] out = new java.lang.reflect.Field[n];
        int i = 0;
        int k = 0;
        while (i < n)
        {
            String nm = declaredFieldAt0(this, i);
            if (nm != null)
            {
                try
                {
                    out[k] = getDeclaredField(nm);
                    k += 1;
                }
                catch (NoSuchFieldException e)
                {
                    // declared in the classfile but not resolvable through the field registry -- skip it
                }
            }
            i += 1;
        }
        if (k == n)
        {
            return out;
        }
        java.lang.reflect.Field[] trimmed = new java.lang.reflect.Field[k];
        int j = 0;
        while (j < k)
        {
            trimmed[j] = out[j];
            j += 1;
        }
        return trimmed;
    }

    /**
     * The PUBLIC fields of this class and its superclasses, as stock. Walks the chain most-derived first; a
     * field HIDDEN by a subclass declaration is reported once, by the most-derived declaration, which is what
     * stock does and what a caller reading values expects.
     */
    public java.lang.reflect.Field[] getFields()
    {
        java.lang.reflect.Field[] acc = new java.lang.reflect.Field[64];
        int k = 0;
        Class<?> c = this;
        int hops = 0;
        while (c != null && hops < 24)
        {
            java.lang.reflect.Field[] own = c.getDeclaredFields();
            int i = 0;
            while (i < own.length && k < acc.length)
            {
                if ((own[i].getModifiers() & 0x0001) != 0)      // ACC_PUBLIC
                {
                    boolean hidden = false;
                    int j = 0;
                    while (j < k)
                    {
                        if (acc[j].getName().equals(own[i].getName()))
                        {
                            hidden = true;                      // a more-derived declaration already won
                            break;
                        }
                        j += 1;
                    }
                    if (!hidden)
                    {
                        acc[k] = own[i];
                        k += 1;
                    }
                }
                i += 1;
            }
            c = c.getSuperclass();
            hops += 1;
        }
        java.lang.reflect.Field[] out = new java.lang.reflect.Field[k];
        int j = 0;
        while (j < k)
        {
            out[j] = acc[j];
            j += 1;
        }
        return out;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredFieldAt}): the n-th declared field's NAME. */
    private static native String declaredFieldAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredFieldCount}): how many fields it declares. */
    private static native long declaredFieldCount0(Class<?> c);

    public java.lang.reflect.Field getDeclaredField(String name) throws NoSuchFieldException
    {
        byte[] nb = jdk.internal.misc.MetalNames.utf8(name);
        int mods = fieldMods0(this, nb);
        if (mods < 0)
        {
            throw new NoSuchFieldException(name);
        }
        return new java.lang.reflect.Field(this, name, mods, fieldTypeChar0(this, nb));
    }

    /**
     * The PUBLIC field named {@code name} declared by this class or inherited from a superclass, or throws
     * {@code NoSuchFieldException} if none is accessible. Unlike {@link #getDeclaredField} (which returns any
     * field of this class regardless of access), {@code getField} enforces public visibility and walks the
     * superclass chain — the reflection access-control rule these tests probe. (Interface-constant fields and
     * static fields are not yet enumerated on metal — see reflection arc M1/M2.)
     */
    public java.lang.reflect.Field getField(String name) throws NoSuchFieldException
    {
        if (name == null)
        {
            throw new NullPointerException();
        }
        int mods = fieldModifiers(name);                // this class's own instance field flags, or -1 if absent
        if (mods >= 0 && (mods & 0x0001) != 0)          // ACC_PUBLIC
        {
            return new java.lang.reflect.Field(this, name, mods, fieldTypeChar(name));
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * The declared method named {@code name} whose parameters are EXACTLY {@code parameterTypes}, as stock
     * matches it, or {@code NoSuchMethodException}.
     *
     * <p>IT USED TO IGNORE {@code parameterTypes} and answer the first method of that name, so with overloads
     * {@code getDeclaredMethod("m", int.class)} could hand back {@code m(String)}. The declared methods are
     * enumerated with their full descriptors, so the match is on the parameter part of the descriptor.
     */
    public java.lang.reflect.Method getDeclaredMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        java.lang.reflect.Method m = declaredMethodMatching(this, name, paramDesc(parameterTypes), false);
        if (m == null)
        {
            throw new NoSuchMethodException(getName() + "." + name + paramDesc(parameterTypes));
        }
        return m;
    }

    /**
     * Stock's {@code getMethod}: the PUBLIC method named {@code name} with exactly {@code parameterTypes}, from
     * this class, its superclasses, then its interfaces -- the order {@link #getMethods} collects in, so a
     * subclass's override wins.
     */
    public java.lang.reflect.Method getMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        String want = paramDesc(parameterTypes);
        Class<?> c = this;
        while (c != null)
        {
            java.lang.reflect.Method m = declaredMethodMatching(c, name, want, true);
            if (m != null)
            {
                return m;
            }
            c = c.getSuperclass();
        }
        java.lang.reflect.Method m = interfaceMethodMatching(this, name, want);
        if (m != null)
        {
            return m;
        }
        throw new NoSuchMethodException(getName() + "." + name + want);
    }

    /** {@code c}'s own method named {@code name} whose descriptor begins with {@code paramDesc}, or null. */
    private static java.lang.reflect.Method declaredMethodMatching(Class<?> c, String name, String paramDesc,
            boolean publicOnly)
    {
        int n = (int) declaredMethodCount0(c);
        int i = 0;
        while (i < n)
        {
            String nm = declaredMethodAt0(c, i);
            String ds = declaredMethodDescAt0(c, i);
            if (nm != null && ds != null && nm.equals(name) && ds.startsWith(paramDesc))
            {
                try
                {
                    java.lang.reflect.Method m = java.lang.reflect.Method.resolve(c, nm, ds);
                    if (!publicOnly || java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    {
                        return m;
                    }
                }
                catch (NoSuchMethodException e)
                {
                    // declared but unresolvable here (a native with no helper): not a match
                }
            }
            i += 1;
        }
        return null;
    }

    /** {@link #getMethod}'s interface half: the superinterfaces of {@code c} and of its superclasses. */
    private static java.lang.reflect.Method interfaceMethodMatching(Class<?> c, String name, String paramDesc)
    {
        Class<?> k = c;
        while (k != null)
        {
            Class<?>[] ifs = k.getInterfaces();
            int i = 0;
            while (i < ifs.length)
            {
                java.lang.reflect.Method m = declaredMethodMatching(ifs[i], name, paramDesc, true);
                if (m == null)
                {
                    m = interfaceMethodMatching(ifs[i], name, paramDesc);
                }
                if (m != null)
                {
                    return m;
                }
                i += 1;
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** The parameter part of a method descriptor, {@code (Ljava/lang/String;I)}, for these types. */
    private static String paramDesc(Class<?>[] types)
    {
        StringBuilder sb = new StringBuilder("(");
        int n = types == null ? 0 : types.length;
        int i = 0;
        while (i < n)
        {
            sb.append(typeDesc(types[i]));
            i += 1;
        }
        return sb.append(')').toString();
    }

    /** The field descriptor of one type: {@code I}, {@code [J}, {@code Ljava/lang/String;}. */
    private static String typeDesc(Class<?> t)
    {
        if (t.isPrimitive())
        {
            String n = t.getName();
            if (n.equals("int"))     { return "I"; }
            if (n.equals("long"))    { return "J"; }
            if (n.equals("boolean")) { return "Z"; }
            if (n.equals("byte"))    { return "B"; }
            if (n.equals("char"))    { return "C"; }
            if (n.equals("short"))   { return "S"; }
            if (n.equals("float"))   { return "F"; }
            if (n.equals("double"))  { return "D"; }
            return "V";
        }
        if (t.isArray())
        {
            return t.getName().replace('.', '/');      // "[Ljava.lang.String;" -> "[Ljava/lang/String;"
        }
        return "L" + t.getName().replace('.', '/') + ";";
    }

    /**
     * The declared constructor taking {@code parameterTypes}, matched by <em>arity</em> only for now (first
     * {@code <init>} with that parameter count), or throws {@code NoSuchMethodException}. Returns a
     * {@code Constructor} that can reflectively {@code newInstance}.
     */
    public java.lang.reflect.Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        // EXACT, by descriptor: arity alone answered (String) for getDeclaredConstructor(int.class) when the
        // class declared both. See Loader.ctorWantDesc.
        return java.lang.reflect.Constructor.resolve(this, paramDesc(parameterTypes) + "V");
    }

    /** Stock's {@code getConstructor}: the PUBLIC constructor taking exactly {@code parameterTypes}. */
    public java.lang.reflect.Constructor<T> getConstructor(Class<?>... parameterTypes) throws NoSuchMethodException
    {
        java.lang.reflect.Constructor<T> c = getDeclaredConstructor(parameterTypes);
        if (!java.lang.reflect.Modifier.isPublic(c.getModifiers()))
        {
            throw new NoSuchMethodException(getName() + ".<init>" + paramDesc(parameterTypes));
        }
        return c;
    }

    /** Stock's {@code getConstructors}: the PUBLIC ones of {@link #getDeclaredConstructors}. */
    public java.lang.reflect.Constructor<?>[] getConstructors()
    {
        java.lang.reflect.Constructor<?>[] all = getDeclaredConstructors();
        int k = 0;
        int i = 0;
        while (i < all.length)
        {
            if (java.lang.reflect.Modifier.isPublic(all[i].getModifiers()))
            {
                k += 1;
            }
            i += 1;
        }
        java.lang.reflect.Constructor<?>[] out = new java.lang.reflect.Constructor<?>[k];
        k = 0;
        i = 0;
        while (i < all.length)
        {
            if (java.lang.reflect.Modifier.isPublic(all[i].getModifiers()))
            {
                out[k] = all[i];
                k += 1;
            }
            i += 1;
        }
        return out;
    }

    /**
     * Every constructor DECLARED by this class.
     *
     * <p>Built from the SAME method enumeration {@link #getDeclaredMethods} uses, keeping what that one
     * filters out: {@code <init>} is in the VM's method registry like any other method. No new native is
     * needed -- the descriptor is already available, and the arity {@code Constructor.resolve} wants is read
     * from it rather than guessed.
     *
     * <p>Reached from {@code ReflectionUtils.getDeclaredConstructor}, which streams this array, filters out
     * synthetic entries and requires EXACTLY ONE -- so it must be a real enumeration rather than a one-element
     * array built from {@code getDeclaredConstructor()}: a class with two constructors must answer two, or
     * JUnit's "exactly one" check passes when it should fail.
     */
    public java.lang.reflect.Constructor<?>[] getDeclaredConstructors()
    {
        // A SEPARATE enumeration, because declaredMethodCount0/declaredMethodAt0 FILTER OUT <init> and
        // <clinit> at the VM (getDeclaredMethods must not report them). Built on those, this loop matched
        // nothing and answered an EMPTY array -- and JUnit's getDeclaredConstructor requires exactly one, so
        // "zero" failed the test class rather than the VM, which is how it surfaced.
        int n = (int) declaredCtorCount0(this);
        java.lang.reflect.Constructor<?>[] out = new java.lang.reflect.Constructor<?>[n];
        int i = 0;
        int k = 0;
        while (i < n)
        {
            try
            {
                out[k] = java.lang.reflect.Constructor.resolve(this, declaredCtorDescAt0(this, i));   // exact: by descriptor
                k += 1;
            }
            catch (NoSuchMethodException e)
            {
                // declared but unresolvable here -- skip it rather than return a null element
            }
            i += 1;
        }
        java.lang.reflect.Constructor<?>[] trimmed = new java.lang.reflect.Constructor<?>[k];
        int j = 0;
        while (j < k)
        {
            trimmed[j] = out[j];
            j += 1;
        }
        return trimmed;
    }

    /** VM native: how many CONSTRUCTORS this class declares (declaredMethodCount0 excludes them). */
    private static native long declaredCtorCount0(Class<?> c);

    /** VM native: the n-th declared constructor's DESCRIPTOR, for its arity. */
    private static native String declaredCtorDescAt0(Class<?> c, int want);

    /** Access flags of the named own instance field, or -1 if absent (reflection helper for the field updaters). */
    public int fieldModifiers(String name)
    {
        return fieldMods0(this, jdk.internal.misc.MetalNames.utf8(name));
    }

    /** First char of the named own instance field's JVM type descriptor ('I','J','Z','L','['), or -1. */
    public int fieldTypeChar(String name)
    {
        return fieldTypeChar0(this, jdk.internal.misc.MetalNames.utf8(name));
    }

    /** VM native -> {@code VM.fieldMods}: this class's own instance field {@code name}'s access flags, or -1. */
    static native int fieldMods0(Class c, byte[] name);

    /** VM native -> {@code VM.fieldTypeChar}: first char of that field's JVM type descriptor ('I','J','L',...). */
    static native int fieldTypeChar0(Class c, byte[] name);
}
