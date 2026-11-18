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

import board.bcm2711.Uart;
import classfile.ClassReader;
import magic.Address;
import magic.Magic;

import static vm.ClassBytes.u2;

/**
 * Static fields: the static-field registry ({@code sgTab}) of every loaded class's {class, name, cell address}, its
 * hash index, and resolving a {@code getstatic}/{@code putstatic} -- including an inherited static -- to its cell
 * (JikesRVM: {@code runtime/Statics}, the JTOC). Split out of {@link Loader}.
 */
final class Statics
{
    private Statics()
    {
    }

    // Static-field registry: per loaded class, each static field's {class, name, slot address} so a
    // cross-class getstatic/putstatic (e.g. Long.formatUnsignedLong0 reading Integer.digits) resolves.
    static RVMField[] sgTab;   // global static-field registry (reified: one RVMField per static field)
    static int sgCount;
    static int sgScanCalls;                             // staticSlotOf calls (ALL callers, not just the seeds)
    static int sgScanSteps;                             // ... registry entries compared inside them
    static long psSteps;                                // ... entries compared inside sgCellOf (0k = the probe
    /**
     * Class+name hash index over the static registry, so a static-field lookup is a PROBE rather than a
     * linear walk of all {@code sgCount} entries comparing TWO Utf8 strings against each.
     *
     * <p>MEASURED FIRST, and the counters said two things reading had not. `statT` was 463.914ms cumulative
     * at batch 209 -- the third-largest item in a launcher boot and the one top-level split never looked
     * inside -- and on the demo suite it is **136 sites, every one of them a MISS**: `ps:n=136 miss=136
     * tab=280`, i.e. every call walks the whole table to answer "not here", and {@code patchRelocs} asks
     * again on the next batch because a class loaded later must still resolve. That is why the unres arm's
     * memo does not transfer here: the negative answer is NOT immutable. What an index does instead is make
     * the negative CHEAP -- a probe that lands on an empty bucket, the shape the imap refill already took
     * ({@code chain=0k}).
     *
     * <p>Sound by construction rather than by a claim about when work may be skipped: {@code sgTab[sgCount]}
     * is written and {@code sgCount} incremented immediately after, at the ONE site that appends
     * ({@link #registerStaticFields}) -- no entry is ever re-pointed, so an append-only index cannot go
     * stale. Incremental like {@code dlBucket}.
     *
     * <p>THE LOWEST MATCHING INDEX STILL WINS. The scan returned the first match, head-insertion makes the
     * chain descending, so the chain is searched for the MINIMUM rather than stopped at the first hit -- the
     * same care the Type index and {@code findPdByName}'s index needed. Two entries can share a class+name
     * only if a class reached {@code registerStaticFields} twice, and silently preferring the later cell
     * would be a static read binding to different memory than the {@code <clinit>} wrote through.
     *
     * <p>The watermark resets BESIDE the table in {@code resetLoader} as well as being checked here: a
     * watermark that outlives the table it indexes under-marks SILENTLY, and {@code sgCount} returning to
     * the same value after a reset would make the "went backwards" check alone miss it.
     */
    // 8192, NOT a power of two above MAXREG. The bucket array is refilled with -1 on every rebuild, and
    // `resetLoader` triggers one per LAUNCH -- so an oversized table is 30 x SGTAB stores across the demo
    // suite's 30 programs, paid to hold chains that are already short. sgCount reaches ~280 on the suite and
    // a few thousand on the launcher, so 8192 keeps the average chain under one there and bounds it at 3
    // even at the MAXREG ceiling; `psSteps` reports the truth if that estimate is ever wrong.
    private static final int SGTAB = 8192;               // power of two; chained
    static int[] sgBucket;                       // class+name hash -> first entry index, -1 when empty
    private static int[] sgNext;                         // entry index -> next entry with the same hash
    static int sgIndexed;

    /** Absolute address of the static field referenced by constant-pool Fieldref {@code idx}. */
    static long staticAddr(int idx)
    {
        if (Loader.PARSE_CTX_WATCH)
        {
            Loader.checkParseCtxOwned(5);
        }
        int nameOff = ClassReader.refNameOff(Loader.gView.bytes, Loader.gView.cp, idx);  // Fieldref -> name Utf8 offset
        // THE FAST PATH IS ONLY VALID FOR A REFERENCE TO THIS CLASS. It matches on the field NAME alone, and a
        // classfile's constant pool DEDUPES Utf8 -- so `OtherClass.foo` and this class's own `foo` share one
        // name entry and compare equal here. Without the class check a class that declares a static and reads
        // a same-named static of another class silently gets its OWN cell.
        //
        // picocli's GroupValidationResult is exactly that: it declares SUCCESS_PRESENT/SUCCESS_ABSENT and its
        // <clinit> reads Type.SUCCESS_PRESENT/Type.SUCCESS_ABSENT to build them. Each read returned the
        // still-null static it was in the middle of assigning, so every instance's `type` was null -- and
        // blockingFailure() compares `type` to those constants, so `null == null` reported a BLOCKING FAILURE
        // on a SUCCESS and handed the console launcher a null exception to throw.
        if (Loader.utf8EqAt(Loader.gView.base, Loader.refClassNameOff(idx), Loader.gView.base, Loader.gView.thisNameOff))
        {
            int s = 0;
            while (s < Loader.gsfCount)                        // same class: match by name Utf8 offset
            {
                if (Loader.gsfName[s] == nameOff)
                {
                    if (Loader.STATIC_ADDR_LOG)
                    {
                        Uart.write(Magic.bytes("  ownstatic "));
                        Loader.printNameAt(Loader.gView.base, Loader.gView.thisNameOff);
                        Uart.putc(0x2E);
                        Loader.printNameAt(Loader.gView.base, nameOff);
                        Uart.write(Magic.bytes(" -> "));
                        VM.printHex(Loader.gStatics.plus(s * 8));
                        Uart.putc(0x0A);
                    }
                    return Loader.gStatics.plus(s * 8).toLong();
                }
                s += 1;
            }
        }
        ClassInitializer.noteInitNeeded(ClassRegistry.classRegOf(u2(Loader.gView.base.plus(Loader.gView.cp[idx]))));   // cross-class static access = an active use
        return globalStaticAddr(idx);                   // another class (e.g. Long -> Integer.digits)
    }

    private static long globalStaticAddr(int idx)
    {
        int classOff = Loader.refClassNameOff(idx);
        int nameOff = Loader.mrefNameOff(idx);
        // The COMPILE-TIME twin of globalStaticByRef: identical key, identical predicate, identical answer,
        // and it carried its own copy of the same linear scan. One index serves both -- the same "one call
        // site's fix, two timers" shape the phase-B publish had.
        long hit = sgCellOf(Loader.gView.base, classOff, nameOff);
        if (hit == 0L)
        {
            hit = inheritedStaticCell(Loader.gView.base, classOff, Loader.gView.base, nameOff, 0);   // declared by a supertype (JVMS 5.4.3.2)
        }
        if (hit != 0L)
        {
            if (Loader.STATIC_ADDR_LOG)
            {
                Uart.write(Magic.bytes("  staticaddr "));
                Loader.printNameAt(Loader.gView.base, classOff);
                Uart.putc(0x2E);
                Loader.printNameAt(Loader.gView.base, nameOff);
                Uart.write(Magic.bytes(" -> "));
                VM.printHex(hit);
                Uart.putc(0x0A);
            }
            return hit;
        }
        if (Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeeded(Loader.gView.base.plus(Loader.refClassNameOff(idx))))
        {
            // A LATE compile named a static of a class RTA never pulled. Do NOT load it here: a load parses
            // every blob and can collect, and the code buffer being compiled is not reachable yet -- it would
            // be swept out from under us (the hazard recorded for buildLambdaTib). Note it instead; the
            // caller pulls it once the compile is over and compiles this body AGAIN, with the class present.
            // The doomed first body reads the zero cell, and is discarded before anything can call it.
            return Loader.unresolvedStaticCell();
        }
        if (Loader.compileReuseTib)
        {
            // A LATE compile (lazy body, on-demand method, deferred <clinit>) -- there is no later batch
            // patchRelocs to fill this in, so 0 is not "patch me", it is the final answer. Emitted as-is the
            // access reads ADDRESS 0: the firmware's low-memory shim, whose instructions come back as a
            // plausible-looking 64-bit value. Handed to a dispatch that is exactly a wild branch to a pc no
            // table claims, on whatever stack happens to be running -- which is how `CharacterDataLatin1
            // .instance` (never pulled, because StringLatin1.toUpperCase compiled after its batch) crashed
            // String.toUpperCase. Point it at a permanently-zero cell instead: the field reads NULL and the
            // use throws an ordinary NPE with a real stack, at the site that actually needs the class.
            Loader.reportUnresolvedStatic(idx);
            return Loader.unresolvedStaticCell();
        }
        return 0L;                                      // batch compile: the reloc pass patches it later
    }

    /** Address of the current class's static field named {@code name}, or 0 if it has none. */
    static long staticSlotByName(byte[] name)
    {
        int s = 0;
        while (s < Loader.gsfCount)
        {
            if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gsfName[s], name))
            {
                return Loader.gStatics.plus(s * 8L).toLong();
            }
            s += 1;
        }
        return 0L;
    }

    /** Slot address of a static field {@code cls.name} from the global registry (any loaded class), or 0. */
    /**
     * Absolute address of the static {@code fnBase/fnLen} declared by the class whose Type is
     * {@code typeAddr}, or 0. The Type-keyed sibling of {@link #staticSlotOf} (which keys on a name pair),
     * for {@code Unsafe.staticFieldOffset(Field)}, whose caller holds a Class rather than a name.
     */
    static long staticSlotOfType(Address typeAddr, Address fnBase, int fnLen)
    {
        int ci = 0;
        while (ci < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[ci].type.EQ(typeAddr))
            {
                int i = 0;
                while (i < sgCount)
                {
                    if (Loader.utf8EqAt(sgTab[i].base, sgTab[i].classOff, ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff)
                            && Utf8.rawEqUtf8(fnBase, fnLen, sgTab[i].base, sgTab[i].nameOff))
                    {
                        return sgTab[i].addr;
                    }
                    i += 1;
                }
            }
            ci += 1;
        }
        return 0L;
    }

    static long staticSlotOf(byte[] cls, byte[] name)
    {
        sgScanCalls += 1;
        int i = 0;
        while (i < sgCount)
        {
            sgScanSteps += 1;
            if (Utf8.utf8IsAtBase(sgTab[i].base, sgTab[i].classOff, cls) && Utf8.utf8IsAtBase(sgTab[i].base, sgTab[i].nameOff, name))
            {
                return sgTab[i].addr;
            }
            i += 1;
        }
        return 0L;
    }

    private static void buildSgIndex()
    {
        if (sgBucket == null || sgIndexed > sgCount)      // rebuilt when resetLoader zeroes the table
        {
            sgBucket = new int[SGTAB];
            sgNext = new int[MethodRegistry.MAXREG];
            int b = 0;
            while (b < SGTAB)
            {
                sgBucket[b] = -1;                        // allocArray does NOT zero its elements on this VM,
                b += 1;                                  //   and 0 is a valid entry index -- fill explicitly
            }
            sgIndexed = 0;
        }
        while (sgIndexed < sgCount)
        {
            int hh = Utf8.utf8Hash2(sgTab[sgIndexed].base, sgTab[sgIndexed].classOff,
                               sgTab[sgIndexed].base, sgTab[sgIndexed].nameOff) & (SGTAB - 1);
            sgNext[sgIndexed] = sgBucket[hh];
            sgBucket[hh] = sgIndexed;
            sgIndexed += 1;
        }
    }

    /**
     * Static-slot address for a field ref given as blob base + Utf8 offsets, or 0. The shared body of the
     * compile-time path ({@link #globalStaticAddr}) and the patch-time one ({@link DynamicLinker#globalStaticByRef}),
     * which carried the identical scan over the identical key.
     *
     * <p>Counted rather than timed, for the reason this arc has now paid for three times: the loop body is
     * two Utf8 compares, so two {@code readCNTPCT_EL0} around it would be a visible share of what they
     * measure.
     */
    static long sgCellOf(Address refBase, int classOff, int nameOff)
    {
        int best = sgIndexOf(refBase, classOff, refBase, nameOff);
        return best < 0 ? 0L : sgTab[best].addr;
    }

    /** The static-registry entry for class {@code clsBase+clsOff} and field {@code nameBase+nameOff} (the two
     *  names may live in different blobs), or -1. The index of {@link #sgCellOf}, shared with
     *  {@link FieldRegistry#fieldIsVolatile}. */
    static int sgIndexOf(Address clsBase, int clsOff, Address nameBase, int nameOff)
    {
        buildSgIndex();
        // The SAME predicate the scan used -- the index only narrows what it is applied to, so a hit is the
        // same entry the scan would have found and a miss still answers 0.
        int best = -1;
        int k = sgBucket[Utf8.utf8Hash2(clsBase, clsOff, nameBase, nameOff) & (SGTAB - 1)];
        while (k >= 0)
        {
            psSteps += 1;
            if (Loader.utf8EqAt(clsBase, clsOff, sgTab[k].base, sgTab[k].classOff)
                    && Loader.utf8EqAt(nameBase, nameOff, sgTab[k].base, sgTab[k].nameOff))
            {
                if (best < 0 || k < best)
                {
                    best = k;                            // lowest index wins, as the scan's first match did
                }
            }
            k = sgNext[k];
        }
        return best;
    }

    /**
     * JVMS 5.4.3.2 FIELD RESOLUTION for a static the named class does not declare itself: search its direct
     * SUPERINTERFACES (recursively), then its SUPERCLASS (recursively, the same way), and answer the first
     * declaring class's cell -- or 0.
     *
     * <p>Static lookup was CLASS-QUALIFIED ONLY, and javac names an inherited static through the class the source
     * wrote: stock {@code CharBuffer.<clinit>} reads {@code getstatic CharBuffer.UNSAFE}, a static DECLARED by
     * {@code java.nio.Buffer}. The miss was not reported on the batch path (it is left for the reloc pass, which
     * looked up the same name and missed again), so the read was a SILENT NULL -- an NPE in CharBuffer.<clinit>
     * with no unresolved-static line anywhere. Instance fields got this walk long ago ({@code globalFieldOffset});
     * statics never did.
     *
     * <p>{@code depth} bounds a malformed or cyclic hierarchy. A declaring class not registered yet answers 0, and
     * the caller's existing unresolved paths (pull-and-retry, reloc) apply.
     */
    static long inheritedStaticCell(Address clsBase, int clsOff, Address nameBase, int nameOff, int depth)
    {
        int k = inheritedStaticIndex(clsBase, clsOff, nameBase, nameOff, depth);
        return k < 0 ? 0L : sgTab[k].addr;
    }

    /** The static-registry entry {@link #inheritedStaticCell} resolves to, or -1: the DECLARING class's cell, so
     *  {@code sgTab[k].base + sgTab[k].classOff} also names the class JVMS 5.5 says to initialize. */
    static int inheritedStaticIndex(Address clsBase, int clsOff, Address nameBase, int nameOff, int depth)
    {
        if (depth > 32)
        {
            return -1;
        }
        if (depth > 0)
        {
            int k = sgIndexOf(clsBase, clsOff, nameBase, nameOff);
            if (k >= 0)
            {
                return k;
            }
        }
        int pd = Loader.findPdByName(clsBase, clsOff);
        if (pd < 0)
        {
            return -1;
        }
        int i = 0;
        while (i < Loader.pdIfN[pd])
        {
            int a = inheritedStaticIndex(Loader.pdBase.get(pd), Loader.pdIfOff[pd * InterfaceInvocation.MAX_DIRECT_IF + i], nameBase, nameOff, depth + 1);
            if (a >= 0)
            {
                return a;
            }
            i += 1;
        }
        if (Loader.pdSuperOff[pd] == 0)
        {
            return -1;
        }
        return inheritedStaticIndex(Loader.pdBase.get(pd), Loader.pdSuperOff[pd], nameBase, nameOff, depth + 1);
    }

    /** Register the current class (its TIB + instance-field layout) for cross-class new/fields. */
    /**
     * PHASE A registration: record the class's Type + TIB + field layout + statics + flattened-vtable STRUCTURE
     * (name/descriptor/slot per slot). The vtable BUFFERS are left 0 -- the method bodies aren't compiled yet;
     * {@link VtableLayout#fillClassVtBuf} fills them in phase B. clVtStart pins where this class's vt entries begin.
     */
    /**
     * Register this class's static fields, so a cross-class {@code getstatic} can find their cells.
     *
     * <p>Split out of {@link Loader#registerClassStructure} because an INTERFACE needs it too and never reaches
     * that method -- {@code loadStructure} builds an interface's registry entry itself and returns early.
     * Interface fields are implicitly {@code public static final}, so an interface that declares any
     * constant at all depends on this.
     */
    static void registerStaticFields()
    {
        if (sgCount + Loader.gsfCount >= MethodRegistry.MAXREG) { Loader.capHalt(Magic.bytes("MethodRegistry.MAXREG-statics"), sgCount); }
        int st = 0;
        while (st < Loader.gsfCount)
        {
            sgTab[sgCount] = new RVMField();
            sgTab[sgCount].base = Loader.gView.base;
            sgTab[sgCount].classOff = Loader.gView.thisNameOff;
            sgTab[sgCount].nameOff = Loader.gsfName[st];
            sgTab[sgCount].addr = Loader.gStatics.plus(st * 8L).toLong();
            sgTab[sgCount].access = Loader.gsfAccess[st];
            sgCount += 1;
            st += 1;
        }
    }
}
