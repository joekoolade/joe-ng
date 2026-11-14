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

    private static int busSem = -1;
    private static int rxTaskId = -1;                    // the RX task, once spawned

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
        board.bcm2711.Uart.write(Magic.bytes("wifi: rx task started (interrupt-driven poll), bring-up glom="));
        VM.printDec(Cyw43.bringupGlom);
        board.bcm2711.Uart.write(Magic.bytes(" subframes="));
        VM.printDec(Cyw43.bringupGlomSubs);
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

    /** True once the RX task runs: from then on it, not the sender, moves the transmit window. */
    static boolean running()
    {
        return rxTaskId >= 0;
    }

    /** The RX task: sleep until the interrupt (or the watchdog), then poll the chip dry. Never returns. */
    static void rxTask()
    {
        dispatchGlom();                                  // subframes the bring-up unpacked but did not consume
        while (true)
        {
            VMScheduler.semWaitTimeout(VM.WIFI_SEM, WATCHDOG_MS);   // the interrupt, or the watchdog
            long t0 = Magic.readCNTPCT_EL0();
            poll();
            net.NetStats.rxBusyTicks = net.NetStats.rxBusyTicks + (Magic.readCNTPCT_EL0() - t0);
        }
    }

    /**
     * Split the superframe in {@link Cyw43#glomBuf} into its subframes and dispatch each one from its own pool
     * buffer, exactly as if it had arrived alone. Returns how many were dispatched.
     */
    private static int dispatchGlom()
    {
        int sent = 0;
        while (Cyw43.glomPending())
        {
            int b = net.RxQueues.allocBuf();
            if (b < 0)
            {
                Cyw43.glomNext(0L, 0);                   // no buffer: drop this subframe
                continue;
            }
            int len = Cyw43.glomNext(net.RxQueues.bufAddr(b), net.RxQueues.FRAME);
            if (len == 0)
            {
                net.RxQueues.freeBuf(b);                 // only malformed subframes were left
                break;
            }
            net.RxQueues.dispatch(b, len);
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
                VMScheduler.kernelYield();                 // consumers hold every buffer: let them drain
                continue;
            }
            long dst = net.RxQueues.bufAddr(b);
            busLock();
            long s0 = Magic.readCNTPCT_EL0();
            int len = Cyw43.readFrameOnce(dst, net.RxQueues.FRAME);
            net.NetStats.rxSdioTicks = net.NetStats.rxSdioTicks + (Magic.readCNTPCT_EL0() - s0);
            if (len == 0)
            {
                Cyw43.clearFrameInts();                  // chip empty: clear SDIOD + SDHCI status
                len = Cyw43.readFrameOnce(dst, net.RxQueues.FRAME);
                if (len == 0)
                {
                    Cyw43.flushTxLocked();               // drained and cleared: NOW send what waits (brcmfmac's
                                                         //   DPC order; see Cyw43.sendFrame)
                    board.bcm2711.Gic.enableSpi(board.bcm2711.Bcm2711.SDIO_SPI);   // re-arm: done
                    busUnlock();
                    net.RxQueues.freeBuf(b);
                    return;
                }
            }
            if (Cyw43.isGlomDesc(dst))
            {
                long g0 = Magic.readCNTPCT_EL0();
                int total = Cyw43.readSuperframe(dst, len);   // still under the bus lock: it follows in the FIFO
                net.NetStats.rxSdioTicks = net.NetStats.rxSdioTicks + (Magic.readCNTPCT_EL0() - g0);
                busUnlock();
                net.RxQueues.freeBuf(b);
                if (total > 0)
                {
                    n = n + dispatchGlom();
                }
                continue;
            }
            busUnlock();
            net.RxQueues.dispatch(b, len);
            n = n + 1;
            if (n % BUDGET == 0)
            {
                VMScheduler.kernelYield();                 // over budget: stay masked, let others run, continue
            }
        }
    }
}
