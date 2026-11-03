/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-02
 */
package board.cyw43;

import magic.Magic;
import vm.VM;
import vm.VMScheduler;

/**
 * The WiFi receive path after bring-up, shaped like a Linux NAPI driver. The ISR ({@link Cyw43#onIrq})
 * only masks the SDIO interrupt at the GIC and posts {@code WIFI_SEM} -- {@code napi_schedule}. This
 * class's kernel task is the poller: it reads frames until the chip has none left (yielding every
 * {@link #BUDGET} frames so it cannot starve the cores), hands each to {@link net.RxQueues#dispatch}, and
 * only when the chip is empty clears the interrupt status, looks once more (a frame that landed between
 * the last read and the clear would otherwise sit unannounced) and unmasks the interrupt --
 * {@code napi_complete_done}.
 *
 * <p>It also wakes every {@link #WATCHDOG_MS} without an interrupt and polls anyway, so a lost edge costs
 * that much latency rather than the connection.
 *
 * <p>The SDIO bus has one owner at a time: {@link #busLock} serialises the poller's frame reads against
 * transmits from any other task (a frame read is several CMD53s, and the host controller runs one command
 * at a time). Before {@link #start} everything runs on the boot task and the lock is a no-op.
 */
public final class WlanPoll
{
    private WlanPoll() {}

    private static final int BUDGET = 32;                // frames per poll before yielding
    private static final int WATCHDOG_MS = 100;          // poll even without an interrupt this often
    private static final int STORM_REPORT = 2000;        // MEASUREMENT: print the storm sample after this many empty polls

    private static int busSem = -1;
    private static int rxTaskId = -1;                    // the RX task, once spawned

    // What the poller MEASURED.
    public static int polls;                             // poll passes (interrupt or watchdog)
    public static int irqPolls;                          // ... woken by the interrupt
    public static int frames;                            // frames read from the chip
    public static int rechecks;                          // frames found by the re-check after clearing status

    // MEASUREMENT (RX heartbeat): is the poller running, and if not, where is it stuck? Ticks are CNTPCT.
    public static int phase;                             // 1 sem wait, 2 bus wait, 3 chip read, 4 dispatch, 5 yield, 6 pool empty
    private static long lastBeat;                        // when the loop last made progress (phase change)
    private static long maxGap;                          // longest time between two beats
    private static long maxGapEnd;                       // when that gap ended
    private static int maxGapPhase;                      // the phase the task sat in during it
    private static long maxDispatch;                     // longest single dispatch
    private static long maxBusWait;                      // longest busLock wait on the RX task

    /** Take the SDIO bus (no-op before {@link #start}). */
    static void busLock()
    {
        if (busSem >= 0)
        {
            VMScheduler.semWait(busSem);
        }
    }

    /** Release the SDIO bus. */
    static void busUnlock()
    {
        if (busSem >= 0)
        {
            VMScheduler.kernelPost(busSem);
        }
    }

    /**
     * Hand the chip to the poller: build the queues and the bus lock, then spawn the RX task. From here on no
     * code may call {@link Cyw43#waitFrameIrq}; every receive goes through {@link net.RxQueues}. Returns false
     * (and leaves the synchronous path in charge) if the task's entry was not baked into the image.
     */
    static boolean start()
    {
        long entry = VM.netRxTaskAddr;
        if (entry == 1L)
        {
            rxTask();                                    // never runs: makes the writer compile + stash rxTask
        }
        if (entry == 0L)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: rx task not in the image -- staying synchronous\n"));
            return false;
        }
        net.RxQueues.init();
        net.Tcp.init();
        busSem = VMScheduler.kernelSem(1);
        net.RxQueues.active = true;
        rxTaskId = VMScheduler.spawnKernel(entry);
        board.bcm2711.Uart.write(Magic.bytes("wifi: rx task started (interrupt-driven poll); bring-up credit waits="));
        VM.printDec(Cyw43.creditWaits());
        board.bcm2711.Uart.write(Magic.bytes(" forced="));
        VM.printDec(Cyw43.creditForced());
        board.bcm2711.Uart.write(Magic.bytes(" txSeq="));
        VM.printDec(Cyw43.txSeqNow());
        board.bcm2711.Uart.write(Magic.bytes(" txMax="));
        VM.printDec(Cyw43.txMaxNow());
        board.bcm2711.Uart.write(Magic.bytes("\n"));
        return true;
    }

    /**
     * True when the caller is the RX task. It also transmits now (TCP ACKs from {@link net.Tcp#input}), and it
     * must not wait for transmit credit: the frames that open the window are the ones it would be reading.
     */
    static boolean onRxTask()
    {
        return rxTaskId >= 0 && VMScheduler.kernelTaskId() == rxTaskId;
    }

    /** MEASUREMENT: the RX task moved to phase {@code p}; account the time spent in the previous one. */
    private static void beat(int p)
    {
        long now = Magic.readCNTPCT_EL0();
        if (lastBeat != 0L)
        {
            long gap = now - lastBeat;
            // A sem wait is idle by design; only count it once it overruns the watchdog by far.
            if (gap > maxGap && (phase != 1 || gap > (Magic.readCNTFRQ_EL0() / 1000L) * (WATCHDOG_MS * 3)))
            {
                maxGap = gap;
                maxGapEnd = now;
                maxGapPhase = phase;
            }
        }
        lastBeat = now;
        phase = p;
    }

    /**
     * MEASUREMENT: print the poller's heartbeat. Times are ms relative to {@code t0} (a CNTPCT stamp, e.g. the
     * connection's start) so they line up with the TCP trace.
     */
    public static void printBeat(long t0)
    {
        long tick = Magic.readCNTFRQ_EL0() / 1000L;
        long now = Magic.readCNTPCT_EL0();
        board.bcm2711.Uart.write(Magic.bytes("rx: task polls="));
        VM.printDec(polls);
        board.bcm2711.Uart.write(Magic.bytes(" irq="));
        VM.printDec(irqPolls);
        board.bcm2711.Uart.write(Magic.bytes(" frames="));
        VM.printDec(frames);
        board.bcm2711.Uart.write(Magic.bytes(" poolEmpty="));
        VM.printDec(net.RxQueues.dropPool);
        board.bcm2711.Uart.write(Magic.bytes(" phase="));
        VM.printDec(phase);
        board.bcm2711.Uart.write(Magic.bytes(" sinceBeat="));
        VM.printDec((int) ((now - lastBeat) / tick));
        board.bcm2711.Uart.write(Magic.bytes("ms maxGap="));
        VM.printDec((int) (maxGap / tick));
        board.bcm2711.Uart.write(Magic.bytes("ms inPhase="));
        VM.printDec(maxGapPhase);
        board.bcm2711.Uart.write(Magic.bytes(" endedAt="));
        VM.printDec(maxGapEnd == 0L ? -1 : (int) ((maxGapEnd - t0) / tick));
        board.bcm2711.Uart.write(Magic.bytes("ms maxDispatch="));
        VM.printDec((int) (maxDispatch / tick));
        board.bcm2711.Uart.write(Magic.bytes("ms maxBusWait="));
        VM.printDec((int) (maxBusWait / tick));
        board.bcm2711.Uart.write(Magic.bytes("ms\n"));
        board.bcm2711.Uart.write(Magic.bytes("rx: f2 badHdr="));
        VM.printDec(Cyw43.rxBadHdr);
        board.bcm2711.Uart.write(Magic.bytes(" oversize="));
        VM.printDec(Cyw43.rxOversize);
        board.bcm2711.Uart.write(Magic.bytes(" maxLen="));
        VM.printDec(Cyw43.rxOversizeMax);
        board.bcm2711.Uart.write(Magic.bytes(" ch="));
        VM.printDec(Cyw43.rxOversizeCh);
        board.bcm2711.Uart.write(Magic.bytes(" glom="));
        VM.printDec(Cyw43.rxGlom);
        board.bcm2711.Uart.write(Magic.bytes(" glomDesc="));
        VM.printDec(Cyw43.rxGlomDesc);
        board.bcm2711.Uart.write(Magic.bytes(" glomSub="));
        VM.printDec(Cyw43.rxGlomSub);
        board.bcm2711.Uart.write(Magic.bytes(" glomMaxSubs="));
        VM.printDec(Cyw43.rxGlomMaxSubs);
        board.bcm2711.Uart.write(Magic.bytes(" glomBad="));
        VM.printDec(Cyw43.rxGlomBad);
        int i = 0;
        while (i < Cyw43.rxBadHdr && i < Cyw43.rxBadHw.length)
        {
            board.bcm2711.Uart.write(Magic.bytes(" hw="));
            VM.printHex(Cyw43.rxBadHw[i] & 0xFFFFFFFFL);
            i = i + 1;
        }
        board.bcm2711.Uart.write(Magic.bytes("\n"));
    }

    /** The RX task: sleep until the interrupt (or the watchdog), then poll the chip dry. Never returns. */
    static void rxTask()
    {
        board.bcm2711.Sdio.rxSpTop = Magic.readSP();     // MEASUREMENT: RX task stack depth
        board.bcm2711.Sdio.rxSpMin = board.bcm2711.Sdio.rxSpTop;
        while (true)
        {
            beat(1);
            if (VMScheduler.semWaitTimeout(VM.WIFI_SEM, WATCHDOG_MS))
            {
                irqPolls = irqPolls + 1;
            }
            polls = polls + 1;
            poll();
        }
    }

    /**
     * Split the superframe in {@link Cyw43#glomBuf} into its subframes and dispatch each one from its own pool
     * buffer, exactly as if it had arrived alone. Returns how many were dispatched.
     */
    private static int dispatchGlom()
    {
        long g = Cyw43.glomBuf;
        int off = 0;
        int sent = 0;
        int i = 0;
        while (i < Cyw43.glomCount)
        {
            int chunk = Cyw43.glomLens[i];
            int start = off;
            if (i == 0)
            {
                start = off + (Magic.load8(g + 7) & 0xFF);   // the first subframe follows the superframe header
            }
            off = off + chunk;
            i = i + 1;
            if (start + 12 > off)
            {
                Cyw43.rxGlomBad = Cyw43.rxGlomBad + 1;
                continue;
            }
            int hw = Magic.load32(g + start);
            int sub = hw & 0xFFFF;
            int nsub = (hw >> 16) & 0xFFFF;
            if (sub < 12 || ((sub ^ nsub) & 0xFFFF) != 0xFFFF || start + sub > off || sub > net.RxQueues.FRAME)
            {
                Cyw43.rxGlomBad = Cyw43.rxGlomBad + 1;
                continue;
            }
            int b = net.RxQueues.allocBuf();
            if (b < 0)
            {
                net.RxQueues.dropPool = net.RxQueues.dropPool + 1;
                continue;
            }
            long dst = net.RxQueues.bufAddr(b);
            int k = 0;
            while (k < sub)
            {
                Magic.store8(dst + k, Magic.load8(g + start + k));
                k = k + 1;
            }
            frames = frames + 1;
            Cyw43.rxGlomSub = Cyw43.rxGlomSub + 1;
            net.RxQueues.dispatch(b, sub);
            sent = sent + 1;
        }
        return sent;
    }

    /** Read and dispatch frames until the chip is empty, then re-arm the interrupt. */
    private static void poll()
    {
        int n = 0;
        while (true)
        {
            int b = net.RxQueues.allocBuf();
            if (b < 0)
            {
                net.RxQueues.dropPool = net.RxQueues.dropPool + 1;
                beat(6);
                VMScheduler.kernelYield();                 // consumers hold every buffer: let them drain
                continue;
            }
            long dst = net.RxQueues.bufAddr(b);
            beat(2);
            long tb = Magic.readCNTPCT_EL0();
            busLock();
            long waited = Magic.readCNTPCT_EL0() - tb;
            if (waited > maxBusWait)
            {
                maxBusWait = waited;
            }
            beat(3);
            int len = Cyw43.readFrameOnce(dst, net.RxQueues.FRAME);
            if (len == 0)
            {
                int hciBefore = board.bcm2711.Sdio.interrupt();   // MEASUREMENT (storm)
                Cyw43.clearFrameInts();                  // chip empty: clear SDIOD + SDHCI status
                len = Cyw43.readFrameOnce(dst, net.RxQueues.FRAME);
                if (len == 0)
                {
                    Cyw43.stormSample(hciBefore);        // MEASUREMENT (storm)
                    if (Cyw43.emptyClears == STORM_REPORT)
                    {
                        Cyw43.stormReport(polls, irqPolls, frames);
                    }
                    board.bcm2711.Gic.enableSpi(board.bcm2711.Bcm2711.SDIO_SPI);   // re-arm: done
                    busUnlock();
                    net.RxQueues.freeBuf(b);
                    return;
                }
                rechecks = rechecks + 1;
            }
            if (Cyw43.isGlomDesc(dst))
            {
                int total = Cyw43.readSuperframe(dst, len);   // still under the bus lock: it follows in the FIFO
                busUnlock();
                net.RxQueues.freeBuf(b);
                if (total > 0)
                {
                    n = n + dispatchGlom();
                }
                continue;
            }
            busUnlock();
            frames = frames + 1;
            beat(4);
            long td = Magic.readCNTPCT_EL0();
            net.RxQueues.dispatch(b, len);
            long took = Magic.readCNTPCT_EL0() - td;
            if (took > maxDispatch)
            {
                maxDispatch = took;
            }
            n = n + 1;
            if (n % BUDGET == 0)
            {
                beat(5);
                VMScheduler.kernelYield();                 // over budget: stay masked, let others run, continue
            }
        }
    }
}
