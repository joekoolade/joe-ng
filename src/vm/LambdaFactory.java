/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import asm.A64Enc;
import compiler.Baseline;
import board.bcm2711.Uart;
import classfile.ClassReader;
import magic.Address;
import magic.Magic;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Lambda synthesis (LambdaMetafactory, and altMetafactory's flags). Split out of {@link Loader}; the synthesised-TIB
 * registry it fills ({@code Loader.lambdaTibRoots}) stays there because proxies and annotation instances share it.
 *
 * <p>A lambda {@code (caps...) -> body(caps..., samArgs...)} is synthesised as a tiny class: a heap object whose
 * fields hold the captures, a Type whose itable maps the functional-interface method to a THUNK that loads the
 * captures into arg registers and tail-calls the lambda-body method. So {@code iface.sam()} on the lambda
 * dispatches (via the normal itable path) into the body with the captured values.
 */
final class LambdaFactory
{
    private LambdaFactory()
    {
    }

    /**
     * {@code LambdaMetafactory}'s flag bits, as its own javadoc names them. An {@code altMetafactory} site
     * carries a flags word as {@code bootstrap_arguments[3]}; a plain {@code metafactory} site has none.
     */
    private static final int LMF_SERIALIZABLE = 1;
    private static final int LMF_MARKERS = 2;
    private static final int LMF_BRIDGES = 4;
    /** Frame a boxing thunk establishes so it can CALL the referent and then the boxer: LR only, but SP must
     *  stay 16-byte aligned. LR sits at STUB_LR_OFF, where {@code VM.unwind} looks for it. */
    private static final int BOX_FRAME = 16;

    /**
     * The {@code altMetafactory} flags word for the indy at {@code idx}, or 0 when the site has none -- a
     * plain {@code metafactory} site, or one whose argument list is too short to carry them.
     *
     * <p>Callers must have established that this IS a lambda indy ({@link Indy#isLambdaIndy}); the classifier is
     * what refuses the flag combinations this VM cannot honour, so anything reaching here is honourable.
     */
    static int lambdaAltFlags(int idx)
    {
        if (Loader.gBsmOff.isZero() || !isAltMetafactory(idx) || Indy.bsmArgCount(idx) < 4)
        {
            return 0;
        }
        return Indy.cpIntAt(Indy.bsmArg(idx, 3));
    }

    /** True if the indy at {@code idx} bootstraps through {@code LambdaMetafactory.altMetafactory}. */
    private static boolean isAltMetafactory(int idx)
    {
        Address e = Indy.bsmEntryOff(Indy.indyBsmIndex(idx));
        int mrefIdx = u2(Loader.gbase.plus(Loader.gcp[u2(e)] + 1));
        return Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/LambdaMetafactory"))
            && Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("altMetafactory"));
    }

    /**
     * True if the {@code altMetafactory} site at {@code idx} has a flags tail this VM can honour.
     *
     * <p>Its first THREE static arguments are byte-for-byte {@code metafactory}'s -- samMethodType,
     * implMethod, instantiatedMethodType -- so the whole of {@link #buildLambdaTib} reads them unchanged and
     * only the TAIL has to be decided:
     *
     * <ul>
     * <li>{@code FLAG_SERIALIZABLE}: the function object must also implement {@code java.io.Serializable}.
     *     HONOURED -- {@link #finishLambdaClass} adds it to the itable directory, so
     *     {@code lambda instanceof Serializable} answers true as it does on stock. Nothing here serialises
     *     anything ({@code java/io/ObjectStream*} is denied), so the marker's only observable is that
     *     answer, and getting it silently wrong is the failure mode this project has paid for repeatedly.</li>
     * <li>{@code FLAG_BRIDGES} with a bridge count of ZERO: an empty list, so nothing to do. javac emits it
     *     alongside {@code FLAG_SERIALIZABLE} for a generic intersection cast, which is how every such site
     *     in java.base reads.</li>
     * <li>{@code FLAG_MARKERS}, a NON-zero bridge count, or any bit outside those two: REFUSED. Each names
     *     extra interfaces or extra methods the function object must carry, and a directory that silently
     *     lacked them would answer a type test wrongly or leave a dispatch with nowhere to go. Refusing
     *     keeps today's behaviour exactly -- the classifier says no, and {@code Baseline.lowerInvokeDynamic}
     *     reports {@code JIT unsupported ... 0xBA} naming the class. Nothing in java.base needs them
     *     (measured: all EIGHT {@code altMetafactory} users read flags=5, bridgeCount=0), so honouring them
     *     would be shipping code that cannot fire.</li>
     * </ul>
     */
    static boolean altFormSupported(int idx)
    {
        int nargs = Indy.bsmArgCount(idx);
        if (nargs < 4)
        {
            return false;                               // no flags word to decide on
        }
        int flags = Indy.cpIntAt(Indy.bsmArg(idx, 3));
        if ((flags & ~(LMF_SERIALIZABLE | LMF_BRIDGES)) != 0)
        {
            return false;                               // FLAG_MARKERS, or a bit this VM has never seen
        }
        if ((flags & LMF_BRIDGES) != 0)
        {
            // The bridge COUNT follows the flags (there is no marker list, or FLAG_MARKERS would be set).
            if (nargs < 5 || Indy.cpIntAt(Indy.bsmArg(idx, 4)) != 0)
            {
                return false;                           // a real bridge list needs real bridge methods
            }
        }
        return true;
    }

    /** JIT buffer of the lambda body (bootstrap_arguments[1] MethodHandle -> its Methodref, same class). */
    private static long lambdaImplBuf(int idx)
    {
        return Loader.resolveCallBuf(lambdaImplMref(idx));
    }

    /** Methodref (cp index) the impl MethodHandle (bootstrap_arguments[1]) points at. */
    static int lambdaImplMref(int idx)
    {
        Address e = Indy.bsmEntryOff(Indy.indyBsmIndex(idx));
        int mhIdx = u2(e.plus(6));                          // bootstrap_arguments[1] = the impl MethodHandle
        return u2(Loader.gbase.plus(Loader.gcp[mhIdx] + 1));              // MethodHandle.reference_index -> Methodref
    }

    /** reference_kind of the impl MethodHandle: 6 = invokeStatic (lambda body / static ref), 5/9 = invokeVirtual/
     *  invokeInterface (an UNBOUND instance method ref, whose receiver is the SAM's first arg). */
    static int lambdaImplKind(int idx)
    {
        Address e = Indy.bsmEntryOff(Indy.indyBsmIndex(idx));
        int mhIdx = u2(e.plus(6));
        return u1(Loader.gbase.plus(Loader.gcp[mhIdx]));                  // MethodHandle.reference_kind
    }

    /** Print the functional interface named by the indy descriptor at {@code idx}. */
    /**
     * Report WHY a lambda's functional interface resolved to Type 0, using the same checked-cause routing as
     * the unresolved-static and null-class-literal reports. The name lives MID-DESCRIPTOR with no length
     * prefix, so it is interned into {@code indyNameBuf} first -- {@code printWhyUnpulled} wants a
     * Utf8-shaped run.
     */
    private static void printWhyLambdaIface(int idx)
    {
        Address clsU = lambdaIfaceNameU(idx);
        if (clsU.isZero())
        {
            Uart.write(Magic.bytes("non-reference return"));
            return;
        }
        Loader.printWhyUnpulled(clsU);
    }

    /** The lambda's functional interface name as a Utf8-shaped run in {@code indyNameBuf}, or 0 if the indy
     *  descriptor returns a primitive. */
    private static Address lambdaIfaceNameU(int idx)
    {
        Address p = Loader.gbase.plus(Loader.mrefDescOff(idx) + 2);
        while (u1(p) != ')')
        {
            p = p.plus(1);
        }
        p = p.plus(1);
        if (u1(p) != 'L')
        {
            return Address.zero();
        }
        Address start = p.plus(1);
        Address q = start;
        while (u1(q) != ';')
        {
            q = q.plus(1);
        }
        int off = Loader.internIndyNameAt(start, q.diff(start).toInt());
        return Loader.indyNameBuf.plus(off);
    }

    /**
     * Name the lambda's IMPLEMENTATION method, {@code class.name}, for the compile-time lambda trace.
     *
     * <p>Without it that trace prints {@code idx=}, which is an indy index WITHIN the class being compiled --
     * useless for finding one lambda in a launcher boot that synthesises thousands. The failure being chased
     * is named exactly ({@code lambda$ofVoidMethod$0}), so the log has to be greppable by that name.
     */
    static void printLambdaImplName(int idx)
    {
        int mref = lambdaImplMref(idx);
        Loader.printNameAt(Loader.gbase, Loader.refClassNameOff(mref));
        Uart.putc(0x2E);
        Loader.printNameAt(Loader.gbase, Loader.mrefNameOff(mref));
    }

    private static void printLambdaIfaceName(int idx)
    {
        Address p = Loader.gbase.plus(Loader.mrefDescOff(idx) + 2);
        while (u1(p) != ')')
        {
            p = p.plus(1);
        }
        p = p.plus(1);
        if (u1(p) != 'L')
        {
            Uart.write(Magic.bytes("<non-reference return>"));
            return;
        }
        p = p.plus(1);
        while (u1(p) != ';')
        {
            Uart.putc(u1(p));
            p = p.plus(1);
        }
    }

    /** Type of the functional interface = the indy descriptor's return class, or 0 if not loaded. */
    private static Address lambdaIfaceType(int idx)
    {
        int descOff = Loader.mrefDescOff(idx);
        Address p = Loader.gbase.plus(descOff + 2);                   // skip the u2 length
        while (u1(p) != ')')
        {
            p = p.plus(1);
        }
        p = p.plus(1);                                         // past ')'
        if (u1(p) != 'L')
        {
            return Address.zero();
        }
        Address nameStart = p.plus(1);
        Address q = nameStart;
        while (u1(q) != ';')
        {
            q = q.plus(1);
        }
        int nameLen = q.diff(nameStart).toInt();
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.rawNameEq(ClassRegistry.clTab[i].base, ClassRegistry.clTab[i].nameOff, nameStart, nameLen))
            {
                return ClassRegistry.clTab[i].type;
            }
            i += 1;
        }
        return Address.zero();
    }

    /** The SAM's descriptor Utf8 offset: bootstrap_arguments[0] MethodType (its name is the indy's name). */
    private static int lambdaSamDescOff(int idx)
    {
        Address e = Indy.bsmEntryOff(Indy.indyBsmIndex(idx));
        int mtIdx = u2(e.plus(4));                          // bootstrap_arguments[0] = MethodType (SAM signature)
        return Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[mtIdx]))];             // MethodType.descriptor_index -> Utf8 offset
    }

    /** SAM parameter count (bootstrap_arguments[0] MethodType) — slice 1c supports only 0. */
    static int lambdaSamArgc(int idx)
    {
        Address e = Indy.bsmEntryOff(Indy.indyBsmIndex(idx));
        int mtIdx = u2(e.plus(4));
        int descOff = Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[mtIdx]))];
        return ClassReader.descParamCount(Loader.gbytes, descOff);
    }

    /** Instance size of a lambda object: header + one field per captured value. */
    /** Debug accessors for the lambda-site trace in MetalSymbols. */
    static int lambdaCaptureCount(int idx)
    {
        return ClassReader.descParamCount(Loader.gbytes, Loader.mrefDescOff(idx));
    }

    static int lambdaSamArgcOf(int idx)
    {
        return lambdaSamArgc(idx);
    }

    static int lambdaImplKindOf(int idx)
    {
        return lambdaImplKind(idx);
    }

    static int lambdaSize(int idx)
    {
        return 16 + ClassReader.descParamCount(Loader.gbytes, Loader.mrefDescOff(idx)) * 8;
    }

    /**
     * Primitive kinds a thunk can box: all eight. Float and double are boxed through the GUEST
     * {@code Float/Double.valueOf}, resolved by name at run time ({@link Loader#boxFloating}): the boxer is image code
     * and naming those classes there pulled their boot-hostile initializers into the image.
     */
    private static boolean boxableKind(int k)
    {
        return k == 'Z' || k == 'B' || k == 'C' || k == 'S' || k == 'I' || k == 'J' || k == 'F' || k == 'D';
    }

    /**
     * UNBOX the arguments a method reference's referent takes as PRIMITIVES while its erased SAM passes them
     * as REFERENCES -- the conversion {@code LambdaMetafactory} inserts on a stock JVM. The thunks boxed a
     * primitive result and never did this, so {@code Integer::sum} summed two {@code Integer} ADDRESSES
     * ({@code ConcurrentHashMap.merge} counted 91,635,776 for 100) and {@code Float::sum} branched wild.
     *
     * <p>Referent parameter {@code p} for {@code p >= pStart} is SAM argument {@code sStart + (p - pStart)},
     * and sits in register {@code regBase + p} once the arm has placed the arguments. Parameters below
     * {@code pStart} are CAPTURES, which are already of the referent's type.
     *
     * <p>ONE LOAD unboxes any of the eight: every wrapper's only instance field is {@code value}, slot 0, and a
     * field slot is 8 bytes holding the value as the compiler keeps it (sign-extended integral, raw float or
     * double bits), so {@code ldr xR, [xR, #16]} leaves exactly what a primitive argument would carry. A
     * widening the referent wants ({@code Integer} into a {@code long} parameter) is already in that slot.
     *
     * <p>A NULL must throw {@code NullPointerException}, and address 16 is mapped RAM here, so the load
     * would silently read garbage. The null path drops this thunk's frame if it has one ({@code framed}) and
     * TAIL-branches to {@code VMBox.unboxNull}: LR still names the SAM call site, so the exception unwinds from
     * there, a frame the walker knows.
     */
    private static int emitUnboxArgs(Address thunk, int w, int idx, int pStart, int sStart, int regBase,
            boolean framed)
    {
        int implDesc = Loader.mrefDescOff(lambdaImplMref(idx));
        int samDesc = lambdaSamDescOff(idx);
        int n = ClassReader.descParamCount(Loader.gbytes, implDesc);
        int p = pStart;
        while (p < n)
        {
            int ik = ClassReader.descParamKind(Loader.gbytes, implDesc, p);
            int sk = ClassReader.descParamKind(Loader.gbytes, samDesc, sStart + (p - pStart));
            if (sk == 'L' && ik != 'L' && ik != '[' && ik != 0)
            {
                int r = regBase + p;
                if (r > 7 || VM.unboxNullAddr == 0L)
                {
                    reportUnboxArg(idx, p, r);
                }
                else
                {
                    int nullLen = framed ? 3 : 2;                                 // [add sp], movz, b
                    w = Loader.emitAt(thunk, w, A64Enc.cbnz(r, 1 + nullLen));           // non-null -> the load
                    if (framed)
                    {
                        w = Loader.emitAt(thunk, w, A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, BOX_FRAME));
                    }
                    w = Loader.emitAt(thunk, w, A64Enc.movz(0, 1, 0));                  // unboxNull's "really throw"
                    long at = thunk.toLong() + w * 4L;
                    w = Loader.emitAt(thunk, w, A64Enc.b((int) ((VM.unboxNullAddr - at) / 4L)));
                    w = Loader.emitAt(thunk, w, A64Enc.ldrx(r, r, 16));                 // xR = wrapper.value
                }
            }
            p += 1;
        }
        return w;
    }

    /** A primitive parameter the thunk cannot unbox: past x7 (a stacked argument) or no helper to throw with. */
    private static void reportUnboxArg(int idx, int p, int r)
    {
        int mref = lambdaImplMref(idx);
        Uart.write(Magic.bytes("  METHOD REF ARG NOT UNBOXED "));
        Loader.printNameAt(Loader.gbase, Loader.refClassNameOff(mref));
        Uart.putc(0x2E);
        Loader.printNameAt(Loader.gbase, Loader.mrefNameOff(mref));
        Uart.write(Magic.bytes(" param="));
        VM.printDec(p);
        if (r > 7)
        {
            Uart.write(Magic.bytes(" (stacked argument)\n"));
        }
        else
        {
            Uart.write(Magic.bytes(" (vm/VMBox.unboxNull not stashed)\n"));
        }
    }

    /** True if the erased SAM at {@code idx} returns a reference while its referent returns a primitive, so
     *  the thunk must box. A constructor reference is excluded by its {@code V} return. */
    private static boolean lambdaNeedsBoxing(int idx)
    {
        char samRet = ClassReader.descReturnKind(Loader.gbytes, lambdaSamDescOff(idx));
        if (samRet != 'L' && samRet != '[')
        {
            return false;
        }
        char implRet = ClassReader.descReturnKind(Loader.gbytes, Loader.mrefDescOff(lambdaImplMref(idx)));
        return implRet != 'V' && implRet != 'L' && implRet != '[';
    }

    /** Descriptor kind char of the referent's return type. */
    private static int lambdaImplRetKind(int idx)
    {
        return ClassReader.descReturnKind(Loader.gbytes, Loader.mrefDescOff(lambdaImplMref(idx)));
    }

    /** Say which method reference needs an adaptation we do not emit, instead of silently emitting a thunk
     *  that reads the wrong register. Named, because the symptom is otherwise a wild value in x0. */
    private static void reportUnboxableRef(int idx, int retKind)
    {
        int mref = lambdaImplMref(idx);
        Uart.write(Magic.bytes("  UNBOXABLE METHOD REF "));
        Loader.printNameAt(Loader.gbase, Loader.refClassNameOff(mref));
        Uart.putc(0x2E);
        Loader.printNameAt(Loader.gbase, Loader.mrefNameOff(mref));
        Uart.write(Magic.bytes(" returns "));
        Uart.putc(retKind);
        Uart.write(Magic.bytes(" into a generic SAM (vm/VMBox.box not stashed)\n"));
    }

    /** Frame prologue for a thunk that must CALL rather than tail-branch. */
    private static int emitBoxPrologue(Address thunk, int w)
    {
        w = Loader.emitAt(thunk, w, A64Enc.subImm(StubFrames.STUB_SP, StubFrames.STUB_SP, BOX_FRAME));
        w = Loader.emitAt(thunk, w, A64Enc.strx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));
        return w;
    }

    /**
     * Epilogue of a boxing thunk: the referent has just been CALLED, so x0 holds its raw primitive. Hand it
     * to {@code VMBox.box} with its descriptor kind and return the reference the SAM's caller expects.
     *
     * <p>It also registers the frame. The thunk now calls twice and the boxer allocates, so an exception (or
     * a GC stack walk) can pass through here; a frame the walker cannot size is what turns a real fault into
     * an unreadable trace.
     */
    private static int emitBoxReturn(Address thunk, int w, int retKind)
    {
        w = Loader.emitAt(thunk, w, A64Enc.movz(1, retKind, 0));               // x1 = the descriptor kind char
        Address at = thunk.plus(w * 4L);
        at.store(A64Enc.bl((int) ((VM.boxPrimAddr - at.toLong()) / 4L)));
        w += 1;
        w = Loader.emitAt(thunk, w, A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));
        Address thi = thunk.plus(w * 4L);                               // at the `add sp`: the frame dies here
        w = Loader.emitAt(thunk, w, A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, BOX_FRAME));
        w = Loader.emitAt(thunk, w, A64Enc.ret());
        VM.addJitFrame(thunk.plus(4L), thi, BOX_FRAME, 0L);                                                 // live from just after the `sub sp`
        return w;
    }

    /** Build the synthetic lambda class (thunk + imap + itable dir + Type + TIB); returns the TIB address. */
    /**
     * RESOLVE instead of branching when a hand-emitted thunk's target is 0.
     *
     * <p>COMPILED dispatch sites get {@code dispatchTargetGuard}, whose null arm diverts to VIRTUAL_RESOLVE.
     * A hand-emitted thunk had no such arm: it does {@code ldr x16, vtable[slot]} and {@code br x16}, so a
     * slot RTA pruned reads 0 and branches to 0 -- which the firmware's low-memory shim turns into a silent
     * re-entry of the image entry.
     *
     * <p>THAT FAILURE IS UNTRACEABLE, and specifically so: {@code br} does not write x30, so the
     * BOOT RE-ENTERED report's x30 is a STALE return address from an earlier, healthy call, and every frame
     * it names is an artifact. The launcher's wild branch was mis-attributed for several boots to a perfectly
     * good `blr` in ArraySpliterator.forEachRemaining because of exactly that.
     *
     * <p>The thunk already handles the sibling case: when {@code globalVtableSlot} answers -1 it emits
     * `movz x17, siteIndex` + the trampoline address and lets the shared branch call it. This is the same
     * repair for the case that slipped through -- a slot number that IS valid but whose vtable BUFFER is
     * still 0 because the body was never compiled. Same registers, same trampoline, same first-call
     * resolution against the receiver.
     *
     * <p>Five words, and it cannot make a working thunk fail: it fires only on a target of 0, which could
     * not have branched anywhere useful.
     */
    private static int emitThunkTargetGuard(Address thunk, int w, int mref)
    {
        Address tramp = VirtualDispatch.virtualTramp();
        if (tramp.isZero())
        {
            return w;                                   // no trampoline to route to: leave the thunk as it was
        }
        w = Loader.emitAt(thunk, w, A64Enc.cbnz(16, 5));       // target non-zero -> skip the four words below
        w = Loader.emitAt(thunk, w, A64Enc.movz(17, VirtualDispatch.virtualSiteIndex(mref), 0));   // x17 = site (name+descriptor)
        w = Loader.emitTrampAddr(thunk, w, tramp);             // x16 = the late-dispatch trampoline (3 words)
        return w;
    }

    /**
     * Initialize the class a CONSTRUCTOR REFERENCE is about to instantiate (JVMS 5.5).
     *
     * <p>Called from the hand-emitted kind-8 thunk, which allocates and runs {@code <init>} itself rather
     * than going through {@code resolveUnresolvedNew} -- the helper where this rule already lives.
     *
     * <p>{@code reg} is baked into the thunk as an immediate at build time. That is sound for the same
     * reason the TIB beside it is: {@code resetLoader} rebuilds {@code clTab} per LAUNCH, and a thunk built
     * in one launch has already had its baked TIB invalidated by the next -- so a stale index here is not a
     * new exposure, and {@code ensureClinit} bounds-checks the index besides.
     */
    static void ctorRefInit(int reg)
    {
        ClassInitializer.ensureClinit(reg);
    }

    /**
     * The constructor-reference initialization helper is not in this image, so the thunk cannot run the
     * target's {@code <clinit>} before its {@code <init>}. Says which class is affected: the consequence is
     * a constructor reading its own class's statics as null, which surfaces arbitrarily far away.
     */
    private static void reportCtorRefNoInitHelper(int cr)
    {
        Uart.write(Magic.bytes("\n  CTOR-REF CANNOT INITIALIZE (its <init> may read null statics): "));
        if (ClassRegistry.clTab != null && cr >= 0 && cr < ClassRegistry.clCount && ClassRegistry.clTab[cr] != null)
        {
            Loader.printNameAt(ClassRegistry.clTab[cr].base, ClassRegistry.clTab[cr].nameOff);
        }
        else
        {
            Uart.write(Magic.bytes("<unregistered>"));
        }
        Uart.write(Magic.bytes(" -- vm/VM.ctorRefInit0 was not stashed\n"));
    }

    /** Names a constructor-reference target whose TIB is not built yet -- see the call site for why. */
    private static void reportLambdaCtorNoTib(int cr)
    {
        Uart.write(Magic.bytes("\n  CTOR-REF TARGET HAS NO TIB (the reference would build a null-TIB object): "));
        if (ClassRegistry.clTab != null && cr >= 0 && cr < ClassRegistry.clCount && ClassRegistry.clTab[cr] != null)
        {
            Loader.printNameAt(ClassRegistry.clTab[cr].base, ClassRegistry.clTab[cr].nameOff);
        }
        else
        {
            Uart.write(Magic.bytes("<unregistered>"));
        }
        Uart.putc(0x0A);
    }

    static long buildLambdaTib(int idx)
    {
        Address ifaceType = lambdaIfaceType(idx);
        Address ifaceU = ifaceType.isZero() ? lambdaIfaceNameU(idx) : Address.zero();
        if (ifaceType.isZero() && !ifaceU.isZero() && Loader.lzCompiling && !Loader.lzRetried
                && !VM.dirBytes(ifaceU.plus(2L), u2(ifaceU)).isZero() && Loader.notePullNeeded(ifaceU))
        {
            // A LATE compile met a lambda whose interface is in the classDir but not loaded -- a deferred
            // package (closureDeferred), which the batch pull loop skips by design. Note it for the pull-and-
            // recompile the late path already runs, the same route an unpulled constructor-reference target
            // takes below: NOT demand-loaded here, because a load can collect and this code buffer is not
            // reachable yet. SILENT: the TIB built now is discarded with the body, and only an interface
            // still missing after the pull is a failure worth naming.
        }
        else if (ifaceType.isZero())
        {
            // 0 is also the itable directory's END SENTINEL, so a lambda built with it satisfies NO interface
            // and every dispatch on it throws NPE from the directory miss, far from the indy that caused it.
            // Name it rather than let that happen quietly.
            Uart.write(Magic.bytes("  LAMBDA IFACE UNRESOLVED "));
            printLambdaIfaceName(idx);
            // AND THE CHECKED CAUSE, not just the name. A Type of 0 here has several possible reasons and
            // they call for opposite responses: DENYLISTED means the null is intended, "absent from the
            // classDir" means the jar/dir lookup failed, and "registered" means the class IS loaded and the
            // problem is ORDERING -- the interface arrived after this thunk was built. Naming which one
            // costs two lines and is the difference between a fix and a guess; the same routing already
            // settled `NULL CLASS LITERAL` and `UNRESOLVED STATIC` in one boot each.
            Uart.write(Magic.bytes(" -- "));
            printWhyLambdaIface(idx);
            // WHICH COMPILE. "outside the retry window" has two very different causes and the fixes are
            // opposite: compileReuseTib TRUE means a LATE compile that failed to bracket itself with
            // lzCompiling (the bug fixed three times already -- lazyCompileLocked had the bracket,
            // compileMethodOnDemand and compileSigOnDemand got it in #213, clinitEntryOf later), while FALSE
            // means a BATCH phase-B compile, where there is no retry by design and the interface should have
            // arrived as a probeAll/addIndyIfaceDep dependency instead.
            Uart.write(Magic.bytes(" [reuseTib="));
            VM.printDec(Loader.compileReuseTib ? 1 : 0);
            Uart.write(Magic.bytes(" lzCompiling="));
            VM.printDec(Loader.lzCompiling ? 1 : 0);
            Uart.write(Magic.bytes(" lzRetried="));
            VM.printDec(Loader.lzRetried ? 1 : 0);
            Uart.putc(0x5D);
            Uart.putc(0x0A);
        }
        int nc = ClassReader.descParamCount(Loader.gbytes, Loader.mrefDescOff(idx));   // number of captured values
        int ia = lambdaSamArgc(idx);                                    // SAM (interface method) args
        int kind = lambdaImplKind(idx);
        boolean boxRet = lambdaNeedsBoxing(idx);
        int retKind = boxRet ? lambdaImplRetKind(idx) : 0;
        if (boxRet && (!boxableKind(retKind) || VM.boxPrimAddr == 0L))
        {
            reportUnboxableRef(idx, retKind);
            boxRet = false;                 // an un-adapted result is wrong, but a `bl 0` re-enters the image
        }
        // 128 + the boxing epilogue's 7 words, PLUS two words per SAM argument: the kind-8 constructor-ref
        // arm saves every ctor argument to the frame across Heap.alloc and reloads it before <init>, so its
        // length grows with the arity while every other arm is fixed. At 160 flat a constructor reference
        // with more than ~13 SAM arguments would have written PAST the allocation into whatever code the
        // arena handed out next -- silent, and the worst failure mode this VM has. Nothing reached had that
        // arity, so it never fired; sizing from `ia` removes the cliff rather than moving it.
        // ... and up to five more per SAM argument for an unbox (cbnz, [add sp], movz, b, ldr): see emitUnboxArgs.
        Address thunk = Heap.allocCode(160 + ia * 36);
        int w = 0;
        if (boxRet)
        {
            w = emitBoxPrologue(thunk, w);
        }
        if (kind == 5 || kind == 9)
        {
            // Instance method ref -> vtable-dispatch the referent on its receiver (so overrides resolve).
            if (nc == 0)
            {
                // UNBOUND (String::compareTo): the SAM's first arg is the receiver, the rest are the method
                // args. Shift the SAM args down to x0..x(ia-1) so x0 = receiver, x1.. = method args.
                int j = 0;
                while (j < ia)
                {
                    thunk.plus(w * 4L).store(A64Enc.movReg(j, 1 + j));    // x(j) = samArg[j] (x0 = receiver)
                    w += 1;
                    j += 1;
                }
                w = emitUnboxArgs(thunk, w, idx, 0, 1, 1, boxRet);             // param p = samArg[p+1], in x(1+p)
            }
            else
            {
                // BOUND: the receiver is captured field[0]. ANY FURTHER CAPTURES ARE LEADING ARGUMENTS and
                // must be loaded too -- this branch used to emit only the receiver load, which is correct
                // for a one-capture method reference (obj::method) and SILENTLY DROPS captures 1..nc-1 for
                // a lambda BODY, whose kind is also 5 when javac targets a private nestmate instance method.
                //
                // The symptom is very specific and was reproduced before this was found: capture 0 arrives
                // intact and every later one is garbage, because x1.. still hold whatever the caller left
                // there. picocli's launcher hit it as an Optional local containing an enum constant
                // (VIRTUALRESOLVE FAILED ...$2.map(Function)Optional); see test/jdk/junit/CaptureProbe.
                //
                // Same shape as the kind 6/7 path below: shift the SAM args UP to x(nc)..x(nc+ia-1) first
                // (high->low, so a shift never clobbers a source), then load the captures into x0..x(nc-1)
                // with x0 LAST, since x0 is the object being read from. With nc == 1 the shift is a no-op
                // mov and the load is the single receiver load this used to emit, so a genuine bound method
                // reference is byte-for-byte unchanged.
                int j = ia - 1;
                while (j >= 0)
                {
                    thunk.plus(w * 4L).store(A64Enc.movReg(nc + j, 1 + j));   // x(nc+j) = samArg[j]
                    w += 1;
                    j -= 1;
                }
                int c = nc - 1;
                while (c >= 0)
                {
                    thunk.plus(w * 4L).store(A64Enc.ldrx(c, 0, 16 + c * 8));  // xC = obj.field[c] (x0 last)
                    w += 1;
                    c -= 1;
                }
                w = emitUnboxArgs(thunk, w, idx, nc - 1, 0, 1, boxRet);            // params past the captured ones
            }
            int slot = VtableLayout.globalVtableSlot(lambdaImplMref(idx));                 // vtable slot of the referenced method
            if (slot < 0)
            {
                // The referent's class has no vtable numbering yet -- it was pulled AFTER this method was
                // compiled (a `newresolve`d class, say). Indexing on -1 would load TIB[0], the Type pointer,
                // and branch into the heap. Resolve against the RECEIVER at first call instead, through the
                // same late-dispatch trampoline a guarded call site uses: x0 is already the receiver here.
                if (Loader.LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  lambdaslot late "));
                    Loader.printNameAt(Loader.gbase, Loader.refClassNameOff(lambdaImplMref(idx)));
                    Uart.putc(0x2E);
                    Loader.printNameAt(Loader.gbase, Loader.mrefNameOff(lambdaImplMref(idx)));
                    Uart.putc(0x0A);
                }
                w = Loader.emitAt(thunk, w, A64Enc.movz(17, VirtualDispatch.virtualSiteIndex(lambdaImplMref(idx)), 0));
                w = Loader.emitTrampAddr(thunk, w, VirtualDispatch.virtualTramp());
            }
            else
            {
                w = Loader.emitAt(thunk, w, A64Enc.ldrx(16, 0, 0));                          // x16 = recv.tib (TIB@0)
                w = Loader.emitAt(thunk, w, A64Enc.ldrx(16, 16, 8 + slot * 8));              // x16 = vtable[slot]
            }
            w = emitThunkTargetGuard(thunk, w, lambdaImplMref(idx));             // x16 == 0 -> resolve late, not a wild branch
            if (boxRet)
            {
                w = Loader.emitAt(thunk, w, A64Enc.blr(16));                                 // CALL: the result must be boxed
                w = emitBoxReturn(thunk, w, retKind);
            }
            else
            {
                w = Loader.emitAt(thunk, w, A64Enc.br(16));                                  // tail-call
            }
            Heap.publishCode(thunk, thunk.plus(w * 4L));
            return finishLambdaClass(thunk, ifaceType, idx, nc);
        }
        if (kind == 8)
        {
            // CONSTRUCTOR reference (Num::new): alloc the object, set its TIB, run <init>(obj, samArgs), return
            // the object. Unlike the other thunks this makes two CALLS (Heap.alloc, <init>), so it needs a frame
            // to preserve LR and the SAM args across them. (No captures: the ctor args are all SAM args.)
            int cr = ClassRegistry.classRegByName(Loader.refClassNameOff(lambdaImplMref(idx)));    // the class being constructed
            // THE TARGET MAY NOT BE LOADED YET, and this is the late-compile blind spot again: RTA marks a
            // constructor reference's class instantiated at BATCH time (collectBlob's `mk == 8` arm), so a
            // lambda in a body compiled LATER names a class nothing ever pulled. classRegByName then answers
            // -1, and the code below indexed clTab[-1] -- baked code carries no bounds check, so it read
            // garbage and BAKED A ZERO TIB as an immediate, with no reloc for a later batch to patch.
            //
            // The object then came back with a null TIB and failed a dozen frames away inside JUnit
            // (`ClassCastException: class <no Type; tib=0x0>`), which is what made this look like a JUnit bug.
            //
            // Noted for the pull-and-recompile the lazy path already runs -- NOT demand-loaded here: a load
            // parses every blob and can collect, and the code buffer being written is not reachable yet, which
            // is why a demand-load inside this method was tried once and reverted.
            if (cr < 0)
            {
                // Note it and let the retry pull it. SILENT on the first attempt: this is the EXPECTED state
                // for any late-compiled constructor reference, and a report that fires on a run which then
                // works is worse than none. Only a target still missing AFTER the pull is a real failure --
                // and that one still bakes a zero TIB, so it must be said out loud.
                Loader.notePullNeeded(Loader.gbase.plus(Loader.refClassNameOff(lambdaImplMref(idx))));
                if (Loader.lzRetried)
                {
                    reportLambdaCtorNoTib(cr);
                }
                return finishLambdaClass(thunk, ifaceType, idx, nc);            // discarded by the retry
            }
            int size = 16 + ClassRegistry.clTab[cr].fieldCount * 8;
            long ctib = ClassRegistry.clTab[cr].tib.toLong();
            // A ZERO TIB HERE WOULD BE BAKED AS AN IMMEDIATE AND STAY ZERO FOR EVER -- there is no reloc on
            // this store for a later batch to patch, the same trap a class literal for an unpulled class fell
            // into. The object then comes back with a null TIB, and the first thing done with it (a checkcast,
            // a virtual call) fails somewhere else entirely: the console launcher saw it as
            // `ClassCastException: class <no Type; tib=0x0>` inside JUnit, a dozen frames from here.
            //
            // Report it by NAME rather than bake a zero. A class registered without a TIB is one whose phase B
            // has not run yet, so this says exactly which class and when.
            if (ctib == 0L)
            {
                reportLambdaCtorNoTib(cr);              // registered but phase B has not built its TIB yet
            }
            long initBuf = lambdaImplBuf(idx);                               // its <init> buffer (cross-class ok)
            w = emitUnboxArgs(thunk, w, idx, 0, 0, 1, false);            // Foo(int) from a Function<Integer,Foo>: BEFORE the frame
            int frame = ((2 + ia + 1) & ~1) * 8;                            // LR + obj + ia args, 16-byte aligned
            thunk.plus(w * 4L).store(A64Enc.subImm(31, 31, frame)); // sub sp, #frame
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.strx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // str x30,[sp] (LR)
            w += 1;
            int k = 0;
            while (k < ia)
            {
                thunk.plus(w * 4L).store(A64Enc.strx(1 + k, 31, 16 + k * 8)); // save ctor arg k
                w += 1;
                k += 1;
            }
            // JVMS 5.5: CREATING AN INSTANCE IS AN ACTIVE USE, so the class initializes BEFORE the object
            // exists and certainly before the <init> called below. This arm never did it -- a constructor
            // reference was the one instantiation route in the VM with no ensureClinit anywhere on it.
            //
            // THE IDENTICAL DEFECT WAS FIXED ONCE ALREADY, for a deferred `new`: resolveUnresolvedNew calls
            // ensureClinit for exactly this reason, and its comment records the symptom -- `java/util/
            // ArrayList.<init>` reads DEFAULTCAPACITY_EMPTY_ELEMENTDATA, so an uninitialized class produced a
            // list whose elementData was null and whose first add() threw NPE, far from the cause. A
            // constructor reference reaches <init> without going through that helper at all.
            //
            // MEASURED, not assumed: COMPILE_WATCH on a launcher boot shows
            // TestMethodTestDescriptor.<init> emitted at BATCH time (late=0) while its <clinit> is DEFERRED
            // and compiles only on first active use (late=1) -- so the constructor's body exists before the
            // initializer has run, and that constructor's whole job is `getstatic defaultInterceptorCall;
            // putfield interceptorCall`.
            //
            // HERE, not at thunk-build time, and that half is load-bearing: building the thunk is LINKING,
            // and JVMS 5.4 forbids linking from running an initializer -- the ordering inversion this VM
            // already paid four failed fixes for. The call sits after the SAM arguments are saved to the
            // frame and after LR is saved, so an initializer that allocates, compiles or throws is safe:
            // the frame is established and is registered with the unwinder (addJitFrame, below) across
            // exactly this range. It is NOT under the loader lock, deliberately -- clinitEntryOf takes that
            // around the COMPILE only, because an initializer may block on a monitor or spawn threads.
            long initAddr = VM.ctorRefInit0;
            if (initAddr == 0L)
            {
                // The writer stashes this helper on every image (VM.forceCompile keeps it reachable), so 0
                // means the stash is gone -- and emitting `bl 0` would re-enter the image entry rather than
                // fail. Say so; skipping leaves today's behaviour, which is the bug this fixes.
                reportCtorRefNoInitHelper(cr);
            }
            else if (cr > 0xFFFF)
            {
                // A movz carries 16 bits. MAXCLASS is far below that, so this cannot happen -- but a silent
                // truncation here would initialize a DIFFERENT class, which is the shape of wrong answer
                // this VM is least able to diagnose.
                Loader.capHalt(Magic.bytes("ctorref-reg"), cr);
            }
            else
            {
                thunk.plus(w * 4L).store(A64Enc.movz(0, cr, 0)); // x0 = class registry index
                w += 1;
                Address h0 = thunk.plus(w * 4L);
                h0.store(A64Enc.bl((int) ((initAddr - h0.toLong()) / 4L))); // run <clinit> if it has not run
                w += 1;
            }
            thunk.plus(w * 4L).store(A64Enc.movz(0, size, 0)); // x0 = instance size
            w += 1;
            Address h1 = thunk.plus(w * 4L);
            h1.store(A64Enc.bl((int) ((VM.heapAlloc - h1.toLong()) / 4L))); // x0 = Heap.alloc(size)
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.movz(1, (int) (ctib & 0xFFFFL), 0));
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.movk(1, (int) ((ctib >>> 16) & 0xFFFFL), 1)); // x1 = the class TIB
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.strx(1, 0, 0)); // obj.tib = TIB
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.strx(0, 31, 8)); // save obj at [sp+8]
            w += 1;
            k = 0;
            while (k < ia)
            {
                thunk.plus(w * 4L).store(A64Enc.ldrx(1 + k, 31, 16 + k * 8)); // restore ctor arg k
                w += 1;
                k += 1;
            }
            long h2 = thunk.toLong() + w * 4L;
            if (initBuf != 0L)
            {
                // A CODE->CODE edge, pinned for the same reason patchRelocs pins its targets: after this store
                // the displacement inside the thunk is the ONLY record of initBuf, and nothing scans encodings.
                Heap.pinCodeAt(Address.fromLong(initBuf));
                CodeEdges.note(h2, initBuf);
                Address.fromLong(h2).store(A64Enc.bl((int) ((initBuf - h2) / 4L)));                        // <init>(obj, args)
            }
            else
            {
                // The constructed class's <init> isn't compiled yet (its body is a later PHASE-B batch): emit
                // `bl 0` and record a call site so patchRelocs rewrites it to the real <init> — same late-binding
                // the normal lambda-body path (recordTailReloc) uses. Without this the `bl` with initBuf==0
                // overflows imm26 and wild-branches (a constructor method-ref, Box::new, would jump to junk).
                Address.fromLong(h2).store(A64Enc.bl(0));
                Loader.recordCallReloc(h2, lambdaImplMref(idx));
            }
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.ldrx(0, 31, 8)); // x0 = obj (return)
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // restore LR
            w += 1;
            Address tlo = thunk.plus(4L);                                                               // after `sub sp`
            Address thi = thunk.plus(w * 4L);                                                           // at `add sp`
            thunk.plus(w * 4L).store(A64Enc.addImm(31, 31, frame)); // add sp, #frame
            w += 1;
            thunk.plus(w * 4L).store(A64Enc.ret());
            w += 1;
            Heap.publishCode(thunk, thunk.plus(w * 4L));
            VM.addJitFrame(tlo, thi, frame, 0L);        // it calls <init>; an exception from there unwinds through here
            return finishLambdaClass(thunk, ifaceType, idx, nc);
        }
        long implBuf = lambdaImplBuf(idx);
        // The body is lambda$xxx(captures..., samArgs...). On entry x0 = lambda obj, x1..x(ia) = SAM args.
        // Build the thunk: move the SAM args to x(nc)..x(nc+ia-1), load the captures into x0..x(nc-1),
        // then tail-call the body. Shift direction avoids clobbering: dest-source = nc-1, so shift UP
        // (high->low) when nc>=1, DOWN (low->high) when nc==0.
        if (nc >= 1)
        {
            int j = ia - 1;
            while (j >= 0)
            {
                thunk.plus(w * 4L).store(A64Enc.movReg(nc + j, 1 + j));   // x(nc+j) = samArg[j]
                w += 1;
                j -= 1;
            }
        }
        else
        {
            int j = 0;
            while (j < ia)
            {
                thunk.plus(w * 4L).store(A64Enc.movReg(j, 1 + j));        // x(j) = samArg[j]
                w += 1;
                j += 1;
            }
        }
        int c = nc - 1;
        while (c >= 0)
        {
            thunk.plus(w * 4L).store(A64Enc.ldrx(c, 0, 16 + c * 8));   // xC = obj.field[c] (x0 last)
            w += 1;
            c -= 1;
        }
        w = emitUnboxArgs(thunk, w, idx, nc, 0, 0, boxRet);                          // static referent: param p in x(p)
        long bAt = thunk.toLong() + w * 4L;
        if (implBuf != 0L)
        {
            Heap.pinCodeAt(Address.fromLong(implBuf));
            CodeEdges.note(bAt, implBuf);                                        // as above: a tail branch is
            // Boxing makes this a CALL, not a tail branch: the boxer runs after the referent returns.
            Address.fromLong(bAt).store(boxRet
                    ? A64Enc.bl((int) ((implBuf - bAt) / 4L))
                    : A64Enc.b((int) ((implBuf - bAt) / 4L)));              //   still a code->code edge
        }
        else if (boxRet)
        {
            // As below, but the site is a call, so it takes the CALL reloc: patchRelocs must leave the
            // boxing epilogue after it reachable rather than turning the site into a tail branch.
            Address.fromLong(bAt).store(A64Enc.bl(0));
            Loader.recordCallReloc(bAt, lambdaImplMref(idx));
        }
        else
        {
            // A method ref whose cross-class impl isn't registered yet: emit `b 0` and record a tail reloc so
            // patchRelocs rewrites it once every class is compiled (same late-binding as a normal bl call site).
            Address.fromLong(bAt).store(A64Enc.b(0));
            Loader.recordTailReloc(bAt, lambdaImplMref(idx));
        }
        w += 1;
        if (boxRet)
        {
            w = emitBoxReturn(thunk, w, retKind);
        }
        Heap.publishCode(thunk, thunk.plus(w * 4L));
        return finishLambdaClass(thunk, ifaceType, idx, nc);
    }

    private static long finishLambdaClass(Address thunk, Address ifaceType, int idx, int nc)
    {
        // M8 itables: PER-interface itables. The SAM's identity is (indy name, MethodType desc);
        // each directory entry -- the functional interface AND every interface it transitively
        // extends (a BinaryOperator accumulator is invoked as BiFunction by Stream.reduce) --
        // gets its OWN itable with the thunk at that interface's flattened slot of the SAM.
        int samName = Loader.mrefNameOff(idx);
        int samDesc = lambdaSamDescOff(idx);
        int fnReg = ClassRegistry.regOfType(ifaceType);
        int n = fnReg >= 0 ? InterfaceInvocation.ifaceClosureOf(fnReg) : 0;
        // FLAG_SERIALIZABLE: an altMetafactory site whose lambda was cast to an INTERSECTION with
        // java.io.Serializable (every combinator in stock java.util.Comparator is). The marker declares no
        // method, so honouring it is one more DIRECTORY entry and nothing else -- and the entry is what makes
        // `lambda instanceof Serializable` answer true, as it does on stock. Nothing here serialises
        // anything (java/io/ObjectStream* is denied), so that answer is the marker's only observable; leaving
        // it false would be a silent wrong answer of exactly the shape this VM keeps paying for.
        //
        // If the interface is not REGISTERED there is no Type to put in the directory. Say so rather than
        // drop it quietly: the lambda still works (its SAM is reached through the entries above) and only the
        // type test is wrong, which is precisely the case a silent skip would hide. MEASURED rather than
        // pre-empted with a pull -- java/io/Serializable is named as a CONSTANT_Class by every class that
        // declares `implements Serializable`, so it is already registered in practice.
        int serReg = -1;
        if ((lambdaAltFlags(idx) & LMF_SERIALIZABLE) != 0)
        {
            serReg = ClassRegistry.classIndexByName(Magic.bytes("java/io/Serializable"));
            if (serReg >= 0 && (serReg == fnReg || InterfaceInvocation.inIfaceClosure(serReg, n)))
            {
                serReg = -1;                            // already in the closure: one entry, not two
            }
            else if (serReg < 0)
            {
                Uart.write(Magic.bytes("\n  LAMBDA MARKER UNRESOLVED: java/io/Serializable is not registered"));
                Uart.write(Magic.bytes(" -- the lambda runs, `instanceof Serializable` answers false\n"));
            }
        }
        int extra = serReg >= 0 ? 1 : 0;
        Address dir = Heap.allocData((n + 2 + extra) * 16);
        dir.plus(0L).store(ifaceType);              // the functional interface itself
        dir.plus(8L).store(lambdaItableFor(fnReg, samName, samDesc, thunk));
        int e = 1;
        int di = 0;
        while (di < n)
        {
            dir.plus(e * 16L).plus(0L).store(ClassRegistry.clTab[InterfaceInvocation.ifClosureBuf[di]].type);
            dir.plus(e * 16L).plus(8L).store(lambdaItableFor(InterfaceInvocation.ifClosureBuf[di], samName, samDesc, thunk));
            e += 1;
            di += 1;
        }
        if (serReg >= 0)
        {
            // Serializable declares no method, so its itable is a one-word block nothing can index: the
            // directory entry exists to be FOUND by the type test, not to be dispatched through.
            dir.plus(e * 16L).plus(0L).store(ClassRegistry.clTab[serReg].type);
            dir.plus(e * 16L).plus(8L).store(lambdaItableFor(serReg, samName, samDesc, thunk));
            e += 1;
        }
        dir.plus(e * 16L).plus(0L).store(0L);           // sentinel: interfaceType 0 ends the directory
        dir.plus(e * 16L).plus(8L).store(0L);
        // Type { instanceSize, superType=Object, itableDir } -- no depth/display (a lambda is
        // only ever type-checked through its interface dir, so the walk fallback covers it).
        //
        // superType MUST be Object's Type, not 0. typeAssignable walks self -> itable dir -> superType, so a
        // 0 super ends the walk at the lambda's own interfaces: `lambda instanceof Object` answered FALSE,
        // and every aastore of a lambda into an Object[] threw ArrayStoreException. That is not an obscure
        // path -- it is EVERY varargs call taking a lambda, e.g. Arguments.of((Executable) () -> ...) in the
        // stock BasicGZIPInputStreamTest, whose argument array is an Object[].
        Address type = Heap.allocData(ObjectModel.TYPE_SIZE);
        type.plus(0L).store((long) (16 + nc * 8));
        type.plus(ObjectModel.TYPE_SUPER_OFFSET).store(TibBuilder.objectTypeAddr());
        type.plus(16L).store(dir);
        type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_DEPTH_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).store(0L);
        DynamicTypeCheck.buildImplBitmap(type);                           // a lambda's dir interfaces ARE numbered
        // TIB { Type, Object's vtable }. The lambda declares no virtual method of its OWN -- its SAM is
        // reached through the itable directory above -- but it is still an ordinary object, and
        // equals/hashCode/toString on it must select java/lang/Object's implementations (JVMS 5.4.6: the
        // maximally-specific override in the receiver's hierarchy, which is rooted at Object).
        //
        // This TIB used to be ONE WORD, on the premise that "a lambda is only ever type-checked through its
        // interface dir". An Object method on a lambda receiver then had nowhere to resolve: the class
        // registry has no entry (a lambda has no classfile and, exactly as for a real JVM's HIDDEN classes,
        // no binary name to register under), and the itable directory holds only interface methods. The
        // launcher hit it as `DISPATCH ON UNREGISTERED TYPE ... equals(Ljava/lang/Object;)Z` from
        // Stream.sorted(). Identity semantics inherited from Object are exactly right here --
        // LambdaMetafactory specifies the identity of a captured function object as unpredictable.
        int onv = TibBuilder.objectVtableCount();
        Address tib = Heap.allocData(8 + onv * 8);
        tib.plus(0L).store(type);
        TibBuilder.fillObjectVtable(tib);                           // may copy zeros if Object is not compiled yet;
                                                         //   refillSynthTibVtables repairs that at loadAll's end
        if (TibBuilder.lambdaTibRoots != null && TibBuilder.lambdaTibRootN < TibBuilder.lambdaTibRoots.length())
        {
            TibBuilder.lambdaTibRoots.set(TibBuilder.lambdaTibRootN, tib);         // keep this TIB (and, via its trace, Type/dir/itables) a GC root
            TibBuilder.lambdaTibVtCap[TibBuilder.lambdaTibRootN] = onv;   // ... and how many vtable slots it has room for
            TibBuilder.lambdaTibFilled[TibBuilder.lambdaTibRootN] = false;// ... and NOT yet filled (explicit: see the field's note --
                                                    //   allocArray leaves elements undefined on this VM)
            TibBuilder.lambdaHostBase.set(TibBuilder.lambdaTibRootN, Loader.gbase); // ... and the class being compiled: the lambda's host
            TibBuilder.lambdaHostNameOff[TibBuilder.lambdaTibRootN] = Loader.gThisNameOff;
            TibBuilder.lambdaTibRootN += 1;
        }
        return tib.toLong();
    }

    /** A lambda's itable for interface {@code ir}: zeroes except the thunk at the interface's
     *  flattened slot of the SAM (name/desc offsets in the CURRENT blob). {@code ir < 0} (the
     *  functional interface isn't registered) degrades to a one-slot table holding the thunk. */
    private static long lambdaItableFor(int ir, int samName, int samDesc, Address thunk)
    {
        if (ir < 0)
        {
            Address one = Heap.allocData(8);
            one.store(thunk);
            return one.toLong();
        }
        int count = ClassRegistry.clTab[ir].ifmCount;
        Address it = Heap.allocData(count > 0 ? count * 8 : 8);
        int s = 0;
        while (s < count)
        {
            it.plus(s * 8L).store(0L);
            s += 1;
        }
        int slot = InterfaceInvocation.ifmSlotIn(ir, Loader.gbase, samName, samDesc);
        it.plus((slot >= 0 ? slot : 0) * 8L).store(thunk);
        return it.toLong();
    }
}
