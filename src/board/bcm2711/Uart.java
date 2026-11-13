/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-18
 */
package board.bcm2711;

import magic.Magic;

/**
 * Mini-UART (AUX / UART1) console driver, written as ordinary Java and compiled
 * to A64 by our baseline compiler. Interrupt-driven in both directions once
 * {@link #enableInterrupts} has run (see the TX/RX ring section); polled before. Splitting the boot's UART logic into real
 * methods (called across classes from {@code vm.VM}) is the M2 exercise: genuine
 * {@code BL} calls with arguments and returns, laid out and relocated by the
 * writer (PLAN.md §4 M2).
 */
public final class Uart
{
    private Uart() {}

    /** Bring up the mini-UART: GPIO14/15 → ALT5, 8-bit, clear FIFOs, enable tx/rx. */
    public static void init()
    {
        Magic.store32(Bcm2711.AUX_ENABLES, 1);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IER_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_LCR_REG, 3);
        Magic.store32(Bcm2711.AUX_MU_MCR_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IIR_REG, 0xC6);
        Magic.store32(Bcm2711.AUX_MU_BAUD_REG, baudDivisor());
        Magic.store32(Bcm2711.GPFSEL1, (Bcm2711.ALT5 << 12) | (Bcm2711.ALT5 << 15));
        Magic.store32(Bcm2711.GPIO_PUP_PDN_CNTRL_REG0, 0);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 3);
    }

    /**
     * Baud divisor for 115200, computed from the core clock the firmware reports:
     * mini-UART baud = core_clock / (8*(divisor+1)), so divisor = hz/921600 - 1.
     * The core clock is not something we can assume — it differed across firmware
     * builds and even SD cards on the same board — so we ask rather than guess. If
     * the mailbox does not answer (or reports an implausible rate) we fall back to
     * the compiled-in {@link Bcm2711#BAUD_115200}.
     */
    private static int baudDivisor()
    {
        int hz = Mailbox.coreClockHz();
        coreHz = hz;                                     // kept so boot can report it
        if (hz < 10_000_000 || hz > 1_500_000_000)
        {
            return Bcm2711.BAUD_115200;
        }
        return hz / (8 * 115200) - 1;
    }

    /** Core clock the mailbox reported at init, in Hz (0 = no answer, divisor fell back). */
    public static int coreHz;

    /**
     * Write one byte, translating LF to CRLF. A raw serial console (screen/minicom)
     * treats a bare {@code \n} as line-feed only — no column reset — so without the
     * carriage return each line staircases further right. Emitting {@code \r\n} is
     * the convention for serial output.
     */
    public static void putc(int c)
    {
        if (c == 0x0A)
        {
            putRaw(0x0D);
        }
        putRaw(c);
    }

    /** Write one raw byte: through the TX ring once the AUX interrupt is live, else straight to the FIFO. */
    private static void putRaw(int c)
    {
        if (irqLive == 0)
        {
            putPolled(c);
            return;
        }
        long daif = ringLock();
        pump();                                          // whatever the FIFO takes now, oldest first
        if (txHead == txTail && txReady())
        {
            Magic.store8(Bcm2711.AUX_MU_IO_REG, c);      // idle line: no reason to queue
        }
        else
        {
            while (((txHead + 1) & TX_MASK) == txTail)   // ring full: drain it ourselves at line rate
            {
                pump();
            }
            Magic.store8(TX_RING + txHead, c);
            txHead = (txHead + 1) & TX_MASK;
        }
        if ((daif & 0x80L) != 0L)
        {
            drainPolled();                               // caller has IRQs masked (a fault report, an ISR, a
                                                         //   critical section): nothing may be left to an
                                                         //   interrupt it might never take -- finish now
        }
        setIer();
        ringUnlock(daif);
    }

    /** Write one raw byte, spinning until the TX FIFO can accept it (LSR bit5). The pre-interrupt path. */
    private static void putPolled(int c)
    {
        while (!txReady())
        {
        }
        Magic.store8(Bcm2711.AUX_MU_IO_REG, c);
    }

    // ----- interrupt-driven TX/RX -----------------------------------------------------------------
    // Both directions go through rings in fixed scratch RAM (no heap: putc runs from the first boot
    // instruction on, and from fault reports when the heap may be what broke). The AUX interrupt (GIC SPI
    // 125, routed to core 0) drains the TX ring into the FIFO and the RX FIFO into the RX ring; the
    // scheduler's IRQ path calls onIrq. Before enableInterrupts every byte goes out polled, exactly as it
    // always did. After it, a writer with IRQs ON queues and returns (blocking only on a FULL ring, which it
    // then drains itself); a writer with IRQs MASKED finishes the whole ring synchronously, so a fault report
    // reaches the wire in order even if no interrupt is ever taken again. The ring is guarded by RING_LOCK
    // (+ the local IRQ mask) because a secondary core can print while core 0 runs the ISR.

    /** TX ring: 4 KiB (~355 ms of 115200 baud). RX ring: 1 KiB. Inside the lock page, clear of its words. */
    public static final long TX_RING = 0x0302_1000L;
    public static final long RX_RING = 0x0302_2000L;
    public static final long RING_END = 0x0302_2400L;
    private static final int TX_MASK = 0xFFF;
    private static final int RX_MASK = 0x3FF;
    private static final long RING_LOCK = 0x0302_0100L;

    private static int irqLive;                          // 1 once enableInterrupts has run (no initialiser:
                                                         //   putc runs before any <clinit>)
    private static int txHead;                           // next free TX slot (writers)
    private static int txTail;                           // next byte to send (pump)
    private static int rxHead;                           // next free RX slot (ISR)
    private static int rxTail;                           // next byte to read (readers)
    private static int ier;                              // the AUX_MU_IER value last written
    public static int rxDropped;                         // bytes lost to a full RX ring
    public static int irqCount;                          // AUX interrupts serviced

    /**
     * Turn on the mini-UART interrupt: RX always, TX while the ring holds bytes. The caller has brought up
     * the GIC CPU interface and installed the scheduler's IRQ vectors, whose {@code schedule} dispatches
     * SPI 125 to {@link #onIrq}. Idempotent; the rings start empty.
     */
    public static void enableInterrupts()
    {
        if (irqLive != 0)
        {
            return;
        }
        long daif = Magic.readDaif();
        Magic.disableIrq();
        drainPolled();                                   // nothing to drain yet; keeps the invariant obvious
        txHead = 0;
        txTail = 0;
        rxHead = 0;
        rxTail = 0;
        rxDropped = 0;
        irqCount = 0;
        Magic.store32(RING_LOCK, 0);                     // raw scratch: nothing else zeroes it
        ier = Bcm2711.IER_RX_ENABLE;
        Magic.store32(Bcm2711.AUX_MU_IER_REG, ier);
        Gic.initSpi(Bcm2711.AUX_SPI);
        irqLive = 1;
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
    }

    /**
     * The AUX interrupt body (IRQ context, core 0): move received bytes into the RX ring and queued bytes
     * into the TX FIFO, and stop the TX interrupt once the ring is empty (it is level-triggered on "FIFO has
     * room", so leaving it on with nothing to send would re-fire for ever). Returns true if any byte
     * arrived, so the caller can wake a blocked reader.
     */
    public static boolean onIrq()
    {
        long daif = ringLock();
        irqCount += 1;
        boolean got = pullRx();
        pump();
        setIer();
        ringUnlock(daif);
        return got;
    }

    /** Send everything still queued, spinning on the FIFO. Call before parking, or masking IRQs for good. */
    public static void flush()
    {
        if (irqLive == 0)
        {
            return;
        }
        long daif = ringLock();
        drainPolled();
        setIer();
        ringUnlock(daif);
    }

    /** True if a received byte is waiting. */
    public static boolean rxReady()
    {
        if (irqLive == 0)
        {
            return (Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_RX_READY)) != 0;
        }
        return rxHead != rxTail;
    }

    /**
     * Take one received byte without blocking: 0..255, or -1 if none is waiting. Before the interrupt is
     * live this reads the FIFO directly; after, it reads the ring (and also drains the FIFO, so a reader
     * spinning with IRQs masked still sees input).
     */
    public static int poll()
    {
        if (irqLive == 0)
        {
            if ((Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_RX_READY)) == 0)
            {
                return -1;
            }
            return Magic.load32(Bcm2711.AUX_MU_IO_REG) & 0xFF;
        }
        long daif = ringLock();
        pullRx();
        int b = -1;
        if (rxHead != rxTail)
        {
            b = Magic.load8(RX_RING + rxTail) & 0xFF;
            rxTail = (rxTail + 1) & RX_MASK;
        }
        ringUnlock(daif);
        return b;
    }

    /** True once {@link #enableInterrupts} has run. */
    public static boolean interruptsLive()
    {
        return irqLive != 0;
    }

    /** LSR bit5: the TX FIFO can take a byte. */
    private static boolean txReady()
    {
        return (Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_TX_EMPTY)) != 0;
    }

    /** Move queued bytes into the TX FIFO while it has room. Ring lock held. */
    private static void pump()
    {
        while (txTail != txHead && txReady())
        {
            Magic.store8(Bcm2711.AUX_MU_IO_REG, Magic.load8(TX_RING + txTail));
            txTail = (txTail + 1) & TX_MASK;
        }
    }

    /** Send the whole TX ring, spinning on the FIFO. Ring lock held. */
    private static void drainPolled()
    {
        while (txTail != txHead)
        {
            pump();
        }
    }

    /** Empty the RX FIFO into the ring (dropping, and counting, what does not fit). Ring lock held. */
    private static boolean pullRx()
    {
        boolean got = false;
        while ((Magic.load32(Bcm2711.AUX_MU_LSR_REG) & (1 << Bcm2711.LSR_RX_READY)) != 0)
        {
            int b = Magic.load32(Bcm2711.AUX_MU_IO_REG) & 0xFF;     // read even when full: clears the source
            int next = (rxHead + 1) & RX_MASK;
            if (next == rxTail)
            {
                rxDropped += 1;
            }
            else
            {
                Magic.store8(RX_RING + rxHead, b);
                rxHead = next;
                got = true;
            }
        }
        return got;
    }

    /** TX interrupt on exactly while bytes are queued; RX always. Writes IER only on a change. Ring lock held. */
    private static void setIer()
    {
        int want = Bcm2711.IER_RX_ENABLE;
        if (txHead != txTail)
        {
            want = want | Bcm2711.IER_TX_ENABLE;
        }
        if (want != ier)
        {
            ier = want;
            Magic.store32(Bcm2711.AUX_MU_IER_REG, want);
        }
    }

    /** Mask IRQs here, then take the cross-core ring lock once more than one core can print. */
    private static long ringLock()
    {
        long daif = Magic.readDaif();
        Magic.disableIrq();
        if (lockArmed != 0)
        {
            Magic.spinLock(RING_LOCK);
        }
        return daif;
    }

    /** Release the ring lock and restore the caller's IRQ mask. */
    private static void ringUnlock(long daif)
    {
        if (lockArmed != 0)
        {
            Magic.spinUnlock(RING_LOCK);
        }
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
    }

    // ----- console lock ---------------------------------------------------------------------------
    // With four cores scheduling, two of them can print at once and the bytes interleave -- which is
    // survivable for a log line and ruinous for a fault report, the one output that has to be readable.
    // A report takes the console for its whole multi-call sequence. Ownership is by CORE and the lock is
    // recursive, so a reporter that locks once still calls write()/putc() inside. Armed only while more
    // than one core schedules: LDAXR/STLXR needs the cacheable MMU map that early boot does not have.

    /** Raw scratch word, 0 = free. Its own cache line in the lock page: 0x00 is LOCK_ADDR, 0x40 SCHED_LOCK,
     *  0x80 HANG_LOCK (this lock sat on 0x80 too, so a hang dump -- which prints -- spun on itself), 0x100
     *  {@link #RING_LOCK}. */
    private static final long UART_LOCK = 0x0302_00C0L;
    private static int lockArmed;                        // 1 once more than one core can print
    private static int lockOwner;                        // core holding the console (armLock sets -1 = free;
                                                         //   no initialiser -- that would need a <clinit>,
                                                         //   which runs later than Uart.init())
    private static int lockDepth;

    /** Free the lock words (raw scratch RAM -- nothing else zeroes them) and arm or disarm cross-core locking. */
    public static void armLock(int on)
    {
        Magic.store32(UART_LOCK, 0);
        Magic.store32(RING_LOCK, 0);
        lockOwner = -1;
        lockDepth = 0;
        lockArmed = on;
    }

    /** Take the console for one whole message. Recursive for this core; a no-op before the lock is armed. */
    public static void lock()
    {
        if (lockArmed == 0)
        {
            return;
        }
        int core = (int) (Magic.readMPIDR() & 3L);
        if (lockOwner == core)
        {
            lockDepth += 1;
            return;
        }
        Magic.spinLock(UART_LOCK);
        lockOwner = core;
        lockDepth = 1;
    }

    /** Release one level of the console lock. */
    public static void unlock()
    {
        if (lockArmed == 0 || lockOwner != (int) (Magic.readMPIDR() & 3L))
        {
            return;
        }
        lockDepth -= 1;
        if (lockDepth <= 0)
        {
            lockOwner = -1;
            Magic.spinUnlock(UART_LOCK);
        }
    }

    /** Write every byte of {@code s} (a real heap byte[], e.g. an interned string literal). */
    public static void write(byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            putc(s[i]);
            i = i + 1;
        }
    }
}
