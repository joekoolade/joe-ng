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
package board;

import board.bcm2711.Gic;
import magic.Address;
import magic.Magic;

/**
 * The console: the board-neutral half of the serial driver -- CRLF, the TX/RX rings, the console and ring locks,
 * the interrupt body. Interrupt-driven in both directions once {@link #enableInterrupts} has run (see the TX/RX
 * ring section); polled before.
 *
 * <p><b>The hardware is the board's.</b> The seven register-level operations ({@code console*} in
 * {@link Platform#OPS}: init, TX ready, RX ready, get, put, interrupt enables, interrupt number) are methods of the
 * {@code --board} platform class -- the Pi's mini-UART ({@code board.bcm2711.MiniUart}), QEMU virt's PL011
 * ({@code board.arm.Pl011}) -- and this class calls them through the addresses the writer stores in the
 * {@code hw*Addr} fields below. So nothing here tests the board, and an image carries only its own UART driver.
 * The fields are image statics, filled at build time: they are valid from the first boot instruction, before any
 * {@code <clinit>}, which is when {@link #putc} must already work.
 */
public final class Uart
{
    private Uart() {}

    /** The board's console operations ({@link Platform#OPS}); filled by the writer, never written at run time. */
    public static long hwInitAddr;        // consoleInit()V
    public static long hwTxReadyAddr;     // consoleTxReady()J: 1 = the TX FIFO can take a byte
    public static long hwRxReadyAddr;     // consoleRxReady()J: 1 = a received byte is waiting
    public static long hwGetAddr;         // consoleGet()J: the received byte
    public static long hwPutAddr;         // consolePut(J)V: send one byte
    public static long hwSetIrqAddr;      // consoleSetIrq(J)V: interrupt enables, as IRQ_RX | IRQ_TX
    public static long hwSpiAddr;         // consoleSpi()J: the INTID the UART raises

    /** Interrupt-enable bits {@code consoleSetIrq} takes: receive, and transmit-has-room. */
    public static final int IRQ_RX = 0x01;
    public static final int IRQ_TX = 0x02;

    /** Bring up the board's console UART, polled, interrupts off. */
    public static void init()
    {
        long u = Magic.call0(hwInitAddr);
    }

    /** Core clock the board's UART init measured, in Hz, for the boot's {@code core NNNMHz} line (0 = none). */
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
            hwPut(c);                                    // idle line: no reason to queue
        }
        else
        {
            while (((txHead + 1) & TX_MASK) == txTail)   // ring full: drain it ourselves at line rate
            {
                pump();
            }
            Address.fromLong(TX_RING).plus(txHead).store((byte) c);
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
        hwPut(c);
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
        Address.fromLong(RING_LOCK).store(0);            // raw scratch: nothing else zeroes it
        ier = IRQ_RX;
        hwSetIer(ier);
        Gic.initSpi(spi());
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
            return hwRxReady();
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
            if (!hwRxReady())
            {
                return -1;
            }
            return hwGet();
        }
        long daif = ringLock();
        pullRx();
        int b = -1;
        if (rxHead != rxTail)
        {
            b = Address.fromLong(RX_RING).plus(rxTail).loadUnsignedByte() & 0xFF;
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

    /** The TX FIFO can take a byte. */
    private static boolean txReady()
    {
        return Magic.call0(hwTxReadyAddr) != 0L;
    }

    /** Move queued bytes into the TX FIFO while it has room. Ring lock held. */
    private static void pump()
    {
        while (txTail != txHead && txReady())
        {
            hwPut(Address.fromLong(TX_RING).plus(txTail).loadUnsignedByte());
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
        while (hwRxReady())
        {
            int b = hwGet();                                 // read even when full: clears the source
            int next = (rxHead + 1) & RX_MASK;
            if (next == rxTail)
            {
                rxDropped += 1;
            }
            else
            {
                Address.fromLong(RX_RING).plus(rxHead).store((byte) b);
                rxHead = next;
                got = true;
            }
        }
        return got;
    }

    /** TX interrupt on exactly while bytes are queued; RX always. Writes IER only on a change. Ring lock held. */
    private static void setIer()
    {
        int want = IRQ_RX;
        if (txHead != txTail)
        {
            want = want | IRQ_TX;
        }
        if (want != ier)
        {
            ier = want;
            hwSetIer(want);
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
        Address.fromLong(UART_LOCK).store(0);
        Address.fromLong(RING_LOCK).store(0);
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

    // ----- the board's UART: register access, through the platform's console operations -----------------------

    /** The interrupt the console UART raises (the mini-UART's AUX SPI, the PL011's SPI 1, ...). */
    public static int spi()
    {
        return (int) Magic.call0(hwSpiAddr);
    }

    private static boolean hwRxReady()
    {
        return Magic.call0(hwRxReadyAddr) != 0L;
    }

    private static int hwGet()
    {
        return (int) Magic.call0(hwGetAddr) & 0xFF;
    }

    private static void hwPut(int c)
    {
        long u = Magic.call2(hwPutAddr, c & 0xFFL, 0L);
    }

    /** Write the interrupt enables, {@link #IRQ_RX} | {@link #IRQ_TX}. */
    private static void hwSetIer(int want)
    {
        long u = Magic.call2(hwSetIrqAddr, want, 0L);
    }
}
