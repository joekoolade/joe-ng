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
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Stack traces (JikesRVM: {@code runtime/StackTrace}): walking the frame chain into {@code StackTraceElement[]}
 * for {@code Thread.getStackTrace()} and a Throwable's backtrace, the per-method PC -&gt; source-line tables built
 * from SourceFile + LineNumberTable when a method compiles, and the PC -&gt; method frame printers the collector,
 * scheduler and unwinder report through. Split out of {@link Loader}.
 */
final class StackTrace
{
    private StackTrace()
    {
    }

    // frameToElement's StackTraceElement layout, resolved once per TIB.
    private static Address steFieldsTib;
    private static long steSize;
    private static long steClassOff;
    private static long steMethodOff;
    private static long steFileOff;
    private static long steLineOff;
    /**
     * Object offset of {@code Throwable.backtrace}, found by name once: 0 until it is known, -1 when the class
     * is registered but has no such field (the eight inline slots are then the whole trace).
     */
    private static long thrBacktraceOff;
    /**
     * Build method {@code i}'s persistent line table from {@code gBcToWord} (bci -> machine word offset, just
     * compiled) and the method's LineNumberTable. Layout: {@code {u32 count, (u32 wordOffset, u32 line) * count}}
     * -- one entry per source-line transition, in code-heap memory (persists, like the method buffer). The
     * frame resolver finds the largest {@code wordOffset <= (pc-base)/4} and reads its line. Returns 0 if the
     * class was compiled without line info (no {@code -g}).
     */
    /** buildLineTable's per-bytecode line scratch (see ClassReader.linesByBci); grown, never shrunk. */
    private static int[] lnAt;

    /** TIB of {@code java/lang/StackTraceElement}, pulling the class if it is not loaded yet (0 only if absent). */
    private static Address steTib()
    {
        int i = Loader.classIndexByName(Magic.bytes("java/lang/StackTraceElement"));
        if (i < 0)
        {
            // PULL IT, do not answer 0. The stack-trace natives build StackTraceElements whatever the program's
            // closure held, and a 0 TIB makes every element a receiver with no Type: its first virtual call
            // (getClassName) went through late dispatch on a garbage itable directory and surfaced as an NPE
            // inside resolveViaItableDir. It hid while the class rode into every batch by reference; deferring
            // the logger (closureDeferred) moved StackWalker -- the referrer -- to a late pull, and
            // SystemLoggerProbe's caller lookup found it.
            VM.loaderLock(VM.LOCK_REFLECT);
            try
            {
                if (Loader.classIndexByName(Magic.bytes("java/lang/StackTraceElement")) < 0)
                {
                    Loader.loadClassIncremental(Magic.bytes("java/lang/StackTraceElement"));
                }
            }
            finally
            {
                VM.loaderUnlock();
            }
            i = Loader.classIndexByName(Magic.bytes("java/lang/StackTraceElement"));
        }
        return i >= 0 ? Loader.clTab[i].tib : Address.zero();
    }

    /**
     * Build one {@code StackTraceElement} for machine PC {@code pc}: resolve its method (a JIT'd method, a
     * {@code <clinit>}, or a writer-compiled image method) to declaringClass / methodName / fileName / line and
     * write those four fields at their slot offsets. Mirrors {@link #printFrameAt}'s lookup, but materialises an
     * object instead of printing. Image frames carry the combined {@code owner/Class.method} as the method name
     * (they sit above the guest frames the caller inspects, so their exact split doesn't matter).
     */
    private static long frameToElement(Address pc, Address steTib)
    {
        // THE BLOCK LOOKUP IS HOISTED, as in printFrameAt and for the same reason: inSameCodeBlock scans every
        // code block, and calling it per candidate made each element O(rgCount * codeBlockN). Pi, 2026-11-05:
        // a failed TLS handshake spent ~465 s here materialising its exceptions' stack traces (hang dump in
        // Heap.codeBlockEndAt), long after the connection was dead. pc's block is the same for every candidate,
        // so "buf is in pc's block" is the range test buf >= pStart; a pc in no block keeps the exact call.
        long pEnd = Heap.codeBlockEndAt(pc).toLong();
        long pStart = pEnd == 0L ? 0L : Heap.codeBlockStartAt(pc).toLong();
        long bestBuf = 0L;
        int bestReg = -1;
        int bestClin = -1;
        int i = 0;
        while (i < Loader.rgCount)
        {
            long b = Loader.rgTab[i].buf;
            if (b != 0L && b <= pc.toLong() && b > bestBuf
                    && (pEnd != 0L ? b >= pStart : inSameCodeBlock(Address.fromLong(b), pc))) { bestBuf = b; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < Loader.clinitN)
        {
            long b = Loader.clinitEntry[c];
            if (b != 0L && b <= pc.toLong() && b > bestBuf
                    && (pEnd != 0L ? b >= pStart : inSameCodeBlock(Address.fromLong(b), pc))) { bestBuf = b; bestClin = c; bestReg = -1; }
            c += 1;
        }
        long clsStr = 0L;
        long methStr = 0L;
        long fileStr = 0L;
        long line = 0L;
        if (bestReg >= 0)
        {
            clsStr = Loader.guestBinaryName(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff + 2L),
                    u2(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff)));
            methStr = Loader.guestStringUtf8(Loader.rgTab[bestReg].base, Loader.rgTab[bestReg].nameOff);
            fileStr = Loader.guestStringUtf8(Loader.rgTab[bestReg].src, 0);
            line = lineAtOffset(Loader.rgTab[bestReg].line, (int) ((pc.toLong() - bestBuf) >> 2));
        }
        else if (bestClin >= 0)
        {
            clsStr = Loader.guestBinaryName(Loader.clinitBase.get(bestClin).plus(Loader.clinitNameOff[bestClin] + 2L),
                    u2(Loader.clinitBase.get(bestClin).plus(Loader.clinitNameOff[bestClin])));
            methStr = Loader.guestString(Magic.bytes("<clinit>"));
        }
        else
        {
            long tab = VM.imageSymTable;                    // writer-compiled VM/board method: image symbol table
            long n = VM.imageSymCount;
            long k = 0;
            while (k < n)
            {
                Address e = Address.fromLong(tab + k * 40L);
                long start = e.loadLong();
                if (pc.toLong() >= start && pc.toLong() < e.plus(8L).loadLong())
                {
                    // The symbol is "owner/Class.method", combined: split it at the LAST '.' (a method name
                    // has none) so the class is a class and the method a method, as on a JVM.
                    Address sym = e.plus(16L).loadAddress();
                    int symLen = u2(sym);
                    int dot = symLen - 1;
                    while (dot > 0 && u1(sym.plus(2L + dot)) != 0x2E)
                    {
                        dot -= 1;
                    }
                    if (dot > 0)
                    {
                        clsStr = Loader.guestBinaryName(sym.plus(2L), dot);
                        methStr = Loader.guestBytes(sym.plus(2L + dot + 1L), symLen - dot - 1);
                    }
                    else
                    {
                        methStr = Loader.guestStringUtf8(sym, 0);
                    }
                    fileStr = Loader.guestStringUtf8(e.plus(24L).loadAddress(), 0);
                    line = lineAtOffset(e.plus(32L).loadLong(), (int) ((pc.toLong() - start) >> 2));
                    k = n;
                }
                else
                {
                    k += 1;
                }
            }
        }
        // declaringClass MUST NOT be null, and this is not tidiness: a caller that walks EVERY element and
        // asks getClassName() gets a null and NPEs inside its own code, naming ITS class rather than this gap.
        // JUnit's ExceptionUtils.pruneStackTrace does exactly that (className.startsWith("org.junit.start.")),
        // and it is what stopped the console launcher while it was REPORTING an engine failure -- so the real
        // failure was replaced by a mystery NPE in the reporter.
        //
        // The image-frame branch above fills in only the method (the combined "owner/Class.method"), on the
        // premise -- stated in this method's own comment -- that such frames "sit above the guest frames the
        // caller inspects, so their exact split doesn't matter". That holds for PRINTING and is false for any
        // caller that reads the fields. A pc found in no table at all leaves BOTH null.
        //
        // The placeholder is deliberately not a plausible class name: it must not collide with a prefix a
        // caller prunes on, and a reader seeing it should know the frame was unnameable rather than believe it.
        if (clsStr == 0L)
        {
            clsStr = Loader.guestString(Magic.bytes("<vm>"));
        }
        if (methStr == 0L)
        {
            methStr = Loader.guestString(Magic.bytes("<unknown>"));
        }
        // The four fields are found BY NAME: StackTraceElement is the JDK 26 class, whose layout puts the
        // class-loader, module and Class-object fields first. Its other fields stay null/0 -- format 0 is
        // stock's "show all", and toString prints no loader or module for a null name.
        if (steTib.NE(steFieldsTib))
        {
            int si = Loader.classIndexByName(Magic.bytes("java/lang/StackTraceElement"));
            steSize = 16L + Loader.clTab[si].fieldCount * 8L;
            steClassOff = Loader.instanceFieldOffset(si, Magic.bytes("declaringClass"));
            steMethodOff = Loader.instanceFieldOffset(si, Magic.bytes("methodName"));
            steFileOff = Loader.instanceFieldOffset(si, Magic.bytes("fileName"));
            steLineOff = Loader.instanceFieldOffset(si, Magic.bytes("lineNumber"));
            steFieldsTib = steTib;
        }
        Address ste = Heap.alloc((int) steSize);
        ste.store(steTib);
        ste.plus(steClassOff).store(clsStr);
        ste.plus(steMethodOff).store(methStr);
        ste.plus(steFileOff).store(fileStr);
        ste.plus(steLineOff).store(line);
        return ste.toLong();
    }

    /**
     * Walk the frame chain from ({@code pc},{@code sp}) with {@link VM#frameSizeAt}: record each frame's PC into
     * {@code arr} (a StackTraceElement[]) if non-0, else just count. Stops at the run-trampoline (so a spawned
     * thread's bottom frame is run(), not the trampoline) and at main()'s caller ({@link VM#isLaunchFrame}). {@code savedX30} (non-0 only for a parked thread) lets
     * the FIRST frame step over a leaf {@code taskYield} whose caller return is in x30, not on the stack.
     */
    private static int traceWalk(long pc, long sp, long savedX30, long arr, Address steTib)
    {
        long cpc = pc;
        long csp = sp;
        boolean first = true;
        int n = 0;
        while (n < 64 && cpc > 0x1000L)
        {
            if (VM.runTrampAddr != 0L && cpc >= VM.runTrampAddr && cpc < VM.runTrampAddr + 64L)
            {
                break;                                     // run-trampoline: end the trace at run()
            }
            if (arr != 0L)
            {
                Address.fromLong(arr).plus(24L).plus(n * 8L).store(frameToElement(Address.fromLong(cpc), steTib));
            }
            n += 1;
            long cfs = VM.frameSizeAt(Address.fromLong(cpc));
            if (cfs == 0L)
            {
                if (first && savedX30 > 0x1000L)
                {
                    cpc = savedX30 - 4L;                   // leaf top frame (taskYield): caller return is x30, SP unchanged
                    first = false;
                    continue;
                }
                break;                                     // top of the resolvable stack
            }
            cpc = Address.fromLong(csp).loadLong() - 4L;                  // caller's return address (the call site)
            csp += cfs;
            first = false;
            if (VM.isLaunchFrame(Address.fromLong(cpc)))
            {
                break;                                     // below main(): end the trace at main, as on a JVM
            }
        }
        return n;
    }

    /**
     * Materialise a {@code StackTraceElement[]} from a Throwable's INLINE backtrace ({@code bt0..bt7} at
     * {@code obj+16..+72}, 0-terminated), which {@link VM#unwind} fills at THROW time -- or, when the trace was
     * deeper than eight frames, from the full-depth {@code long[]} in {@code Throwable.backtrace} that
     * {@code VMUnwind.fillInTrace} records (DeepTraceProbe: a 20-deep throw used to come back as 8 frames).
     *
     * <p>An exception that has been constructed but not yet thrown therefore yields an EMPTY array, which is
     * correct rather than merely convenient: stock semantics would capture at construction, and joe-ng
     * deliberately captures at throw so that propagation allocates nothing. Callers that walk the result are
     * fine with empty -- JUnit's {@code maybeTrimStackTrace}, the reason this exists, loops over the array and
     * returns early when it finds no match.
     */
    static long traceFromThrowable(ObjectReference exc)
    {
        if (exc.isNull())
        {
            return 0L;
        }
        Address tib = steTib();
        Address deep = Address.fromLong(throwableBacktrace(exc));             // every frame, when there were more than eight
        int n = 0;
        if (!deep.isZero())
        {
            n = deep.plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadInt();
        }
        else
        {
            while (n < 8 && exc.toAddress().plus(16L).plus((long) n * 8L).loadLong() != 0L)
            {
                n += 1;
            }
        }
        Address arr = Heap.allocArray(n, 8);
        if (!tib.isZero())
        {
            arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(tib.loadAddress()));
        }
        int i = 0;
        while (i < n)
        {
            Address fpc = !deep.isZero() ? deep.plus(ObjectModel.ARRAY_BASE_OFFSET).plus((long) i * 8L).loadAddress()
                                     : exc.toAddress().plus(16L).plus((long) i * 8L).loadAddress();
            arr.plus(24L).plus((long) i * 8L).store(frameToElement(fpc, tib));
            i += 1;
        }
        return arr.toLong();
    }

    static long throwableBacktraceOff()
    {
        if (thrBacktraceOff == 0L && Loader.clTab != null)
        {
            int ti = Loader.classIndexByName(Magic.bytes("java/lang/Throwable"));
            if (ti >= 0)
            {
                thrBacktraceOff = Loader.instanceFieldOffset(ti, Magic.bytes("backtrace"));   // -1 if absent: cached
            }
        }
        return thrBacktraceOff;
    }

    /**
     * {@code exc}'s full-depth backtrace ({@code long[]} of pcs), or 0 when its trace is the inline slots. The
     * field holds {@code exc} ITSELF for an inline trace (see {@link #newThrowableBacktrace}), and null when none
     * was captured; both answer 0 here.
     */
    static long throwableBacktrace(ObjectReference exc)
    {
        long off = throwableBacktraceOff();
        ObjectReference bt = off > 0L ? exc.toAddress().plus(off).loadObjectReference() : ObjectReference.nullReference();
        return bt.EQ(exc) ? 0L : bt.toLong();
    }

    /**
     * Whether {@code exc} is a VM-allocated exception still waiting for its trace: {@link RuntimeEntrypoints#newExc} sets its
     * backtrace to itself, and nothing has filled the inline slots yet. These, and only these, are filled at the
     * throw ({@code VMUnwind.captureTrace}); a constructed exception has its trace from construction, or none
     * when stock would have none (a false {@code writableStackTrace}, or an overridden {@code fillInStackTrace}).
     */
    static boolean awaitsThrowTrace(ObjectReference exc)
    {
        long off = throwableBacktraceOff();
        return off > 0L && exc.toAddress().plus(off).loadObjectReference().EQ(exc) && exc.toAddress().plus(16L).loadLong() == 0L;
    }

    /**
     * Give {@code exc} a fresh {@code long[n]} backtrace and return it, or 0 when the field is unknown. With
     * {@code n == 0} it records that the trace is the INLINE slots instead, by storing {@code exc} itself:
     * stock's Throwable reads a null backtrace as "no trace captured", so it must be non-null, and a re-capture
     * ({@code fillInStackTrace()} again) that now fits the slots must not leave an older, deeper trace in place.
     */
    static long newThrowableBacktrace(ObjectReference exc, int n)
    {
        long off = throwableBacktraceOff();
        if (off <= 0L)
        {
            return 0L;
        }
        if (n == 0)
        {
            exc.toAddress().plus(off).store(exc);
            return 0L;
        }
        Address arr = Heap.allocArray(n, 8);
        arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.primArrayTib(11));   // long[]
        exc.toAddress().plus(off).store(arr);
        return arr.toLong();
    }

    /** Materialise a {@code StackTraceElement[]} for the frame chain at ({@code pc},{@code sp}). */
    static long buildTrace(long pc, long sp, long savedX30)
    {
        Address tib = steTib();
        int count = traceWalk(pc, sp, savedX30, 0L, tib);  // pass 1: count
        long arr = Heap.allocArray(count, 8).toLong();              // StackTraceElement[count]
        if (!tib.isZero())
        {
            // Give the array the [LStackTraceElement; TIB (element Type = TIB[0]) so a `checkcast
            // [LStackTraceElement;` on a Map.Entry.getValue() result passes instead of walking a null Type.
            Address.fromLong(arr).plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(tib.loadAddress()));
        }
        traceWalk(pc, sp, savedX30, arr, tib);             // pass 2: fill
        return arr;
    }

    /** Print the ONE demand-compiled method (or {@code <clinit>}) containing {@code addr} as a single line
     *  "class.method +0xoff" -- the printStackTrace frame formatter (compact vs {@link #reportMethodAt}). */
    /**
     * Whether {@code pc} lies in the same code block as the compiled buffer {@code buf}.
     *
     * <p>The pc -> method lookups pick the nearest registered buffer at-or-below the pc, which has no upper
     * bound of its own: an address in an UNREGISTERED buffer -- a lambda thunk, a line table, a stub, or
     * plain garbage from a derailed unwind -- silently borrows the name of whatever method sits below it.
     * Every bogus pc then reports as the same method (the one with the highest registered buffer), which is
     * how a fault report came to blame {@code LinkedKeySet.toArray} for addresses that were not code at all,
     * and {@code String.<clinit>+0xC8} for a buffer that was never String's.
     *
     * <p>A method's buffer IS a code block, so the block containing {@code buf} is the method's extent and a
     * pc outside it belongs to something else. An unregistered block ({@code 0}) is ACCEPTED rather than
     * rejected: that is the pre-existing behaviour, and image/native addresses must keep resolving.
     */
    private static boolean inSameCodeBlock(Address buf, Address pc)
    {
        long end = Heap.codeBlockEndAt(buf).toLong();
        return end == 0L || pc.toLong() < end;
    }

    /**
     * Name the nearest registered body BELOW {@code addr}, ignoring the code-block bound. A frame that no
     * table claims is either compiled by a path that never registers it, or registered but rejected because
     * the pc ran past its block end -- and only the distance to the nearest entry tells those apart.
     */
    private static void printNearestBelow(Address addr)
    {
        long best = 0L;
        int bestReg = -1;
        int i = 0;
        while (i < Loader.rgCount)
        {
            if (Loader.rgTab[i].buf != 0L && Loader.rgTab[i].buf <= addr.toLong() && Loader.rgTab[i].buf > best)
            {
                best = Loader.rgTab[i].buf;
                bestReg = i;
            }
            i += 1;
        }
        if (bestReg < 0)
        {
            return;
        }
        Uart.write(Magic.bytes(" after "));
        Loader.writeName(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff + 2), u2(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff)));
        Uart.putc(0x2E);
        Loader.writeName(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].nameOff + 2), u2(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].nameOff)));
        Uart.write(Magic.bytes(" +"));
        VM.printHex(addr.toLong() - best);
        Uart.write(Magic.bytes(" end="));
        VM.printHex(Heap.codeBlockEndAt(Address.fromLong(best)).toLong());
    }

    static void printFrameAt(Address addr)
    {
        // THE BLOCK LOOKUP IS HOISTED, and that is the whole cost of a stack trace. `inSameCodeBlock` calls
        // `Heap.codeBlockEndAt`, which LINEARLY SCANS EVERY CODE BLOCK -- and it was called once per registry
        // entry that beat the running best, so naming ONE frame was O(rgCount * codeBlockN) with both tables
        // growing all boot. The user's report is the signature: each printed frame took longer than the last.
        //
        // addr's block is the same for every candidate, so it is computed ONCE. With it known, "buf is in
        // addr's block" is a RANGE TEST: the scan already requires buf <= addr, so buf >= aStart settles it
        // exactly, with no inner scan at all.
        //
        // When addr is in NO block -- image/baked code, where codeBlockEndAt answers 0 -- the original
        // predicate degenerates to "buf is in no block either", which cannot be answered by a range test. That
        // path keeps the per-candidate call: it is exact, it is rare, and such a frame is about to be named by
        // printImageFrameAt anyway.
        long aEnd = Heap.codeBlockEndAt(addr).toLong();
        long aStart = aEnd == 0L ? 0L : Heap.codeBlockStartAt(addr).toLong();
        long bestBuf = 0L;
        int bestReg = -1;
        int bestClin = -1;
        int i = 0;
        while (i < Loader.rgCount)
        {
            long b = Loader.rgTab[i].buf;
            if (b != 0L && b <= addr.toLong() && b > bestBuf
                    && (aEnd != 0L ? b >= aStart : inSameCodeBlock(Address.fromLong(b), addr))) { bestBuf = b; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < Loader.clinitN)
        {
            long b = Loader.clinitEntry[c];
            if (b != 0L && b <= addr.toLong() && b > bestBuf
                    && (aEnd != 0L ? b >= aStart : inSameCodeBlock(Address.fromLong(b), addr))) { bestBuf = b; bestClin = c; bestReg = -1; }
            c += 1;
        }
        if (bestReg < 0 && bestClin < 0)
        {
            if (!printImageFrameAt(addr))                            // writer-compiled VM/driver code: image symbol table
            {
                Uart.write(Magic.bytes("<unclaimed pc="));
                VM.printHex(addr.toLong());
                printNearestBelow(addr);                        // the only handle on a frame no table claims
                Uart.putc(0x3E);

            }
            return;
        }
        if (bestReg >= 0)
        {
            Loader.writeName(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff + 2),
                    u2(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].classOff)));
            Uart.putc(0x2E);
            Loader.writeName(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].nameOff + 2), u2(Loader.rgTab[bestReg].base.plus(Loader.rgTab[bestReg].nameOff)));
            int line = lineAtOffset(Loader.rgTab[bestReg].line, (int) ((addr.toLong() - bestBuf) >> 2)); // (pc-base)/4 = word offset
            if (!Loader.rgTab[bestReg].src.isZero())
            {
                Uart.putc(0x28);                                 // '('
                Loader.writeName(Loader.rgTab[bestReg].src.plus(2), u2(Loader.rgTab[bestReg].src));   // SourceFile filename
                if (line >= 0)
                {
                    Uart.putc(0x3A);                             // ':'
                    VM.printDec(line);
                }
                Uart.putc(0x29);                                 // ')'
            }
        }
        else
        {
            Loader.writeName(Loader.clinitBase.get(bestClin).plus(Loader.clinitNameOff[bestClin] + 2),
                    u2(Loader.clinitBase.get(bestClin).plus(Loader.clinitNameOff[bestClin])));
            Uart.write(Magic.bytes(".<clinit>"));
        }
        Uart.write(Magic.bytes(" [pc="));                        // debug: absolute PC + offset into the method
        VM.printHex(addr.toLong());                                       // printHex already prints the "0x" prefix
        Uart.write(Magic.bytes(" +"));
        VM.printHex(addr.toLong() - bestBuf);
        Uart.putc(0x5D);                                         // ']'
    }

    /**
     * Resolve a writer-compiled (image) code address via the embedded image symbol table (built by
     * ImageBuilder): entries {@code {codeStart, codeEnd, nameAddr, srcAddr, lineAddr}} (5 longs). Prints
     * {@code owner/Class.method(SourceFile.java:line) [pc=... +off]} like a loaded frame, or returns false
     * if {@code addr} is in no image method. Linear scan (traces are rare).
     */
    private static boolean printImageFrameAt(Address addr)
    {
        long tab = VM.imageSymTable;
        if (tab == 0L)
        {
            return false;
        }
        long n = VM.imageSymCount;
        long i = 0;
        while (i < n)
        {
            Address e = Address.fromLong(tab + i * 40L);                           // 5 longs per entry
            long start = e.loadLong();
            if (addr.toLong() >= start && addr.toLong() < e.plus(8L).loadLong())
            {
                Address nameAddr = e.plus(16L).loadAddress();
                Address srcAddr = e.plus(24L).loadAddress();
                Loader.writeName(nameAddr.plus(2), u2(nameAddr));    // "owner/Class.method"
                int srcLen = u2(srcAddr);
                if (srcLen > 0)
                {
                    Uart.putc(0x28);                             // '('
                    Loader.writeName(srcAddr.plus(2), srcLen);       // SourceFile filename
                    int line = lineAtOffset(e.plus(32L).loadLong(), (int) ((addr.toLong() - start) >> 2));
                    if (line >= 0)
                    {
                        Uart.putc(0x3A);                         // ':'
                        VM.printDec(line);
                    }
                    Uart.putc(0x29);                             // ')'
                }
                Uart.write(Magic.bytes(" [pc="));
                VM.printHex(addr.toLong());
                Uart.write(Magic.bytes(" +"));
                VM.printHex(addr.toLong() - start);
                Uart.putc(0x5D);
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** Address of the current class's SourceFile filename Utf8 (persists in the blob), or 0 if none. */
    static Address sourceFileAddr()
    {
        int off = ClassReader.sourceFileNameOff(Loader.gbytes, Loader.gcp, Loader.gAfterCp, Magic.bytes("SourceFile"));
        return off < 0 ? Address.zero() : Loader.gbase.plus(off);
    }

    static long buildLineTable(int i)
    {
        if (Loader.gBcToWord == null)
        {
            return 0L;
        }
        int codeBodyOff = (int) (Loader.mCode[i] - Loader.gbase.toLong()) - 8;   // Code attr body (max_stack) sits 8 bytes before code[]
        int lntOff = ClassReader.lineNumberTableOff(Loader.gbytes, Loader.gcp, codeBodyOff, Magic.bytes("LineNumberTable"));
        if (lntOff < 0)
        {
            return 0L;
        }
        int len = Loader.gBcToWord.length;
        if (lnAt == null || lnAt.length < len)
        {
            lnAt = new int[len < 4096 ? 4096 : len];      // scratch, reused: compiles hold the loader lock
        }
        ClassReader.linesByBci(Loader.gbytes, lntOff, lnAt, len);
        int n = 0;
        int prev = -1;
        int bci = 0;
        while (bci < len)                                 // pass 1: count line transitions
        {
            if (Loader.gBcToWord[bci] >= 0)
            {
                int line = lnAt[bci];
                if (line != prev)
                {
                    n += 1;
                    prev = line;
                }
            }
            bci += 1;
        }
        if (4 + n * 8 >= 0x80000)
        {
            Uart.write(Magic.bytes("  HUGE lineTable n="));
            VM.printDec(n);
            Uart.write(Magic.bytes(" for "));
            Loader.printCurrentClass();
            Uart.putc(0x0A);
        }
        Address tab = Heap.allocCode(4 + n * 8);
        tab.store(n);
        Address w = tab.plus(4);
        prev = -1;
        bci = 0;
        while (bci < len)                                 // pass 2: emit (wordOffset, line) at each transition
        {
            if (Loader.gBcToWord[bci] >= 0)
            {
                int line = lnAt[bci];
                if (line != prev)
                {
                    w.store(Loader.gBcToWord[bci]);
                    w.plus(4).store(line);
                    w = w.plus(8);
                    prev = line;
                }
            }
            bci += 1;
        }
        return tab.toLong();
    }

    /** Source line for machine word offset {@code wordOff} within a method's line table, or -1. */
    private static int lineAtOffset(long tab, int wordOff)
    {
        if (tab == 0L)
        {
            return -1;
        }
        int n = Address.fromLong(tab).loadInt();
        Address w = Address.fromLong(tab + 4);
        int best = -1;
        int bestOff = -1;
        int i = 0;
        while (i < n)
        {
            int off = w.loadInt();
            if (off <= wordOff && off > bestOff)
            {
                bestOff = off;
                best = w.plus(4).loadInt();
            }
            w = w.plus(8);
            i += 1;
        }
        return best;
    }
}
