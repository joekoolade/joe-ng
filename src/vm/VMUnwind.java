package vm;

import magic.Magic;
import board.bcm2711.Uart;
import static vm.VM.*;   // exception-table state + shared helpers stay in VM: handlerTable/Count, jitHandler*,
                         // unwindLog/Logged, unwindLocBuf, faultDepth, and printHex/printStr/frameSizeAt/
                         // jitRegLocalsAt/instanceOf — all reached here by simple name.

/**
 * Stack unwinding + backtrace capture, extracted verbatim from VM.java. {@link #unwind} is the throw engine:
 * it fills the exception's inline backtrace ({@link #captureTrace}), reconstructs the handler's callee-saved
 * locals, then walks frames via {@code VM.frameSizeAt}/{@code VM.jitRegLocalsAt}, resuming into the first
 * covering handler ({@link #findHandler}, matching catch types with {@code VM.instanceOf}) or reporting an
 * uncaught exception at the top. It is reached only by address: image {@code athrow} lowers to the UNWIND
 * helper (WriterSymbols), the metal JIT and the fault path ({@code VM.throwFromFault}) call the writer-stashed
 * {@code VM.unwindAddr}/{@code VM.captureTraceAddr}. All mutable state (the handler/frame tables, the unwind
 * scratch buffer, the fault-depth flag) stays in {@link VM}; only the walk logic lives here.
 */
final class VMUnwind
{
    /**
     * Record the throw-site frame chain into {@code exc}'s inline backtrace (Throwable.bt0..bt7 @ exc+16..+72),
     * first throw only (a re-throw / cross-method unwind won't overwrite). {@code pc} is a code address in the
     * throwing method, {@code sp} its stack pointer. The metal JIT calls this at every {@code athrow} (via the
     * CAPTURE_TRACE helper) so {@code printStackTrace()} has frames even for a same-method inline catch;
     * {@link #unwind} also calls it (idempotent) for the uncaught path. Walks saved LRs with {@link #frameSizeAt}.
     */
    static void captureTrace(long exc, long pc, long sp)
    {
        if (exc <= 0x1000L || Magic.load64(exc + 16L) != 0L)   // boot force-compile passes 0; already captured -> keep
        {
            return;
        }
        long cpc = pc;
        long csp = sp;
        int n = 0;
        while (n < 8 && cpc > 0x1000L)
        {
            Magic.store64(exc + 16L + n * 8L, cpc);
            n += 1;
            long cfs = frameSizeAt(cpc);
            if (cfs == 0L)
            {
                break;                                         // top of the JIT/image stack
            }
            cpc = Magic.load64(csp) - 4L;                      // caller's return address (the call site)
            csp += cfs;
        }
        if (n < 8)
        {
            Magic.store64(exc + 16L + n * 8L, 0L);             // 0-terminate the backtrace
        }
    }

    static void unwind(long exc, long pc, long sp)
    {
        if (unwindLog != 0 && unwindLogged < 24)            // #43: name the FIRST exceptions thrown (root NPE first)
        {
            unwindLogged += 1;
            Uart.write(Magic.bytes("\n  THROW exc="));
            printHex(exc);
            if (exc > 0x1000L && exc < 0x40000000L)
            {
                long tib = Magic.load64(exc);
                if (tib > 0x1000L && tib < 0x40000000L) { Loader.reportClassOfType(Magic.load64(tib)); }
            }
            Uart.write(Magic.bytes(" at 0x"));
            printHex(pc);
            Loader.reportMethodAt(pc);
            Uart.putc(0x0A);
        }
        captureTrace(exc, pc, sp);                     // fill exc's backtrace if not already captured at the throw site
        if (unwindLocBuf == 0L)
        {
            unwindLocBuf = Heap.allocData(16 * 8);     // 16 callee-saved local slots, reused across unwinds
        }
        // Seed with the register snapshot AT THE THROW: this method's OWN prologue saved the throwing frame's
        // x19..x28 (10 slots) at [our_sp + 8 + k*8]. That captures every handler local a shallow-regLocals callee
        // preserved but never re-saved (its value flowed through untouched into our prologue's save). Slots that
        // an intervening frame DID save get overwritten below as we pop that frame.
        long mysp = Magic.readSP();
        long ls = 0L;
        while (ls < 16L)
        {
            if (ls < 10L)
            {
                Magic.store64(unwindLocBuf + ls * 8L, Magic.load64(mysp + 8L + ls * 8L));
            }
            else
            {
                Magic.store64(unwindLocBuf + ls * 8L, 0L);
            }
            ls += 1L;
        }
        while (true)
        {
            long h = findHandler(pc, exc);
            if (h != 0L)
            {
                // unwindLocBuf now holds the handler's live locals: each frame we popped saved its CALLER's
                // x19.. registers, and the last frame popped (the one the handler called into) saved the handler's
                // own locals -- so a catch/finally that reads a local set before the try sees the live value.
                faultDepth = 0;                                            // fault resolved by a handler: a later fault
                                                                           //   (incl. one inside the handler) is FRESH,
                                                                           //   not a nested unwind fault
                Magic.resume(h, sp, exc, jitRegLocalsAt(pc), unwindLocBuf);   // never returns
            }
            long fs = frameSizeAt(pc);
            if (fs == 0L)
            {
                // No frame entry for this pc: the exception reached the TOP uncaught (past main/boot). A valid
                // Throwable here is an EXPECTED uncaught exception (e.g. a JDK test throwing to signal failure) --
                // report it like a JVM ("Exception in thread \"main\" <class>: <message>"), not a VM error. Only
                // an absent/invalid exception object means a real frame-table gap (overflowed/unregistered method).
                long xt = Magic.load64(exc);
                if (xt > 0x1000L)
                {
                    Uart.write(Magic.bytes("\nException in thread \"main\" "));
                    Loader.printClassName(Magic.load64(xt));
                    long msg = Magic.load64(exc + 80L);          // Throwable.detailMessage (after the 8-slot backtrace)
                    if (msg > 0x1000L)
                    {
                        Uart.write(Magic.bytes(": "));
                        printStr(msg);
                    }
                    Uart.putc(0x0A);
                }
                else
                {
                    Uart.write(Magic.bytes("\nUNWIND LOST pc="));   // no valid exception object -> a genuine
                    printHex(pc);                                    //   frame-table gap, not an uncaught throw
                    Uart.write(Magic.bytes(" exc="));
                    printHex(exc);
                    Uart.putc(0x0A);
                }
                // print the captured stack trace (method + SourceFile + line) as printStackTrace does.
                int fi = 0;
                while (fi < 8)
                {
                    long fpc = Magic.load64(exc + 16L + fi * 8L);
                    if (fpc == 0L)
                    {
                        break;
                    }
                    Uart.write(Magic.bytes("  at "));
                    Loader.printFrameAt(fpc);
                    Uart.putc(0x0A);
                    fi += 1;
                }
                while (true)
                {
                    Magic.wfe();    // uncaught at the top
                }
            }
            // Overwrite ONLY the slots this frame saved (its caller's x19.. at [sp+8+k*8]); leave higher slots to
            // the seed / deeper frames. When the caller turns out to be the handler, these ARE its pre-try locals;
            // the frame the handler called into is popped last, so it wins for the slots it saved.
            long nrl = jitRegLocalsAt(pc);
            long k2 = 0L;
            while (k2 < nrl && k2 < 16L)
            {
                Magic.store64(unwindLocBuf + k2 * 8L, Magic.load64(sp + 8L + k2 * 8L));
                k2 += 1L;
            }
            pc = Magic.load64(sp) - 4L;             // the call site (return address - one instruction)
            sp = sp + fs;                           // pop this frame
        }
    }

    private static long findHandler(long pc, long exc)
    {
        long h = findHandlerIn(handlerTable, handlerCount, pc, exc);   // image methods
        if (h != 0L)
        {
            return h;
        }
        return findHandlerIn(jitHandlerTable, jitHandlerCount, pc, exc);   // metal-built / JIT'd methods
    }

    private static long findHandlerIn(long table, long count, long pc, long exc)
    {
        long i = 0L;
        while (i < count)
        {
            long e = table + i * 32L;
            if (pc >= Magic.load64(e) && pc < Magic.load64(e + 8L))
            {
                long catchType = Magic.load64(e + 24L);
                if (catchType == 0L || instanceOf(exc, catchType) != 0)
                {
                    return Magic.load64(e + 16L);
                }
            }
            i = i + 1L;
        }
        return 0L;
    }}
