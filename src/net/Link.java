/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package net;

import magic.Address;
import magic.Magic;

/**
 * The network interface as the stack sees it: send an 802.3 frame, wait for one, and -- for a frame the link
 * received -- say which channel it came on and where its Ethernet frame starts. Everything above this (IPv4, ARP,
 * TCP, DHCP, DNS, the receive queues) works on Ethernet frames and asks here; nothing above parses a link's own
 * framing.
 *
 * <p><b>The link is the board's.</b> Each operation goes through the board's platform class ({@code net*} in
 * {@link board.Platform#OPS}), bound when the image is built: on the Pi the CYW43455 WiFi chip
 * ({@code board.cyw43}), which wraps each frame in SDPCM and BDC headers and also delivers control replies and
 * firmware events; on QEMU {@code virt} {@code board.virt.VirtioNet}, which delivers bare Ethernet frames. A
 * received frame is kept exactly as the link delivered it, and {@link #ethOffset} is where its envelope is
 * unwrapped.
 */
public final class Link
{
    private Link()
    {
    }

    /** The board's link operations ({@link board.Platform#OPS}); filled by the writer, never written at run time. */
    public static long hwPresentAddr;     // netPresent()J: 1 if this machine has the link's hardware
    public static long hwUpAddr;          // netUp(J)V: bring it up (join, DHCP, ARP), publishing net.Ip; 1 = then the link's demo
    public static long hwSendAddr;        // netSend(JJ)V: frame, length
    public static long hwMacAddr;         // netMac()J: our MAC (heap address)
    public static long hwWaitFrameAddr;   // netWaitFrame(JJJ)J: dst, cap, ms -> length or 0
    public static long hwChannelAddr;     // netChannel(JJ)J: CH_* of a received frame
    public static long hwEthOffsetAddr;   // netEthOffset(JJ)J: where a DATA frame's Ethernet header starts
    public static long hwEventAddr;       // netEvent(JJ)V: a link event nothing consumed
    public static long hwLinkFrameAddr;   // netLinkFrame(JJ)V: a data frame that is the link's own business
    public static long hwLinkStatsAddr;   // netLinkStats(JJJ)J: the driver's counters into a long[] (VMStats.net)

    /** How long one receive wait sleeps before answering "nothing yet" (callers loop on 0). */
    public static final int RX_WAIT_MS = 200;

    /** A received frame's channel: a data (Ethernet) frame, a control reply, a link event, or something else. */
    public static final int CH_DATA = 0;
    public static final int CH_CTRL = 1;
    public static final int CH_EVENT = 2;
    public static final int CH_OTHER = 3;

    /** Whether this machine has the board's link (false on QEMU raspi4b, which has no WiFi chip). */
    public static boolean present()
    {
        return Magic.call0(hwPresentAddr) != 0L;
    }

    /**
     * Bring the link up: join (on WiFi), then DHCP and ARP. The scheduler and IRQs must already be running.
     * {@code demo}: go on to the link's built-in demo afterwards (the Pi's HTTP fetch, at the demo suite's end).
     */
    public static void up(boolean demo)
    {
        long u = Magic.call2(hwUpAddr, demo ? 1L : 0L, 0L);
    }

    /** Send a fully built 802.3 frame. */
    public static void send(Address frame, int len)
    {
        long u = Magic.call2(hwSendAddr, frame.toLong(), len);
    }

    /** Our 6-byte MAC (heap address); the link reads it from the hardware the first time. */
    public static Address mac()
    {
        return Address.fromLong(Magic.call0(hwMacAddr));
    }

    /**
     * Wait up to {@code ms} for the next frame straight off the link (before {@link RxQueues} is active, while the
     * caller is the only reader), as the link delivered it. Its length, or 0.
     */
    public static int waitFrame(Address dst, int cap, int ms)
    {
        return (int) Magic.call3(hwWaitFrameAddr, dst.toLong(), cap, ms);
    }

    /**
     * The next frame for a consumer: from its queue {@code q} once {@link RxQueues} is active (with no queue bound,
     * nothing can arrive: sleep and answer 0), else straight off the link. Its length, or 0 within
     * {@link #RX_WAIT_MS}.
     */
    public static int recv(int q, Address dst, int cap)
    {
        if (RxQueues.active)
        {
            if (q < 0)
            {
                vm.VMScheduler.kernelSleep(RX_WAIT_MS);
                return 0;
            }
            return RxQueues.recv(q, dst, cap, RX_WAIT_MS);
        }
        return waitFrame(dst, cap, RX_WAIT_MS);
    }

    /** The channel of the received frame at {@code rx} ({@code len} bytes). */
    public static int channel(Address rx, int len)
    {
        return (int) Magic.call2(hwChannelAddr, rx.toLong(), len);
    }

    /**
     * Where the Ethernet frame starts inside the received frame at {@code rx} ({@code len} bytes), or -1 when it is
     * not a data frame.
     */
    public static int ethOffset(Address rx, int len)
    {
        if (channel(rx, len) != CH_DATA)
        {
            return -1;
        }
        return (int) Magic.call2(hwEthOffsetAddr, rx.toLong(), len);
    }

    /** A link event (CH_EVENT) the queues have no consumer for: the link logs it. */
    public static void event(Address rx, int len)
    {
        long u = Magic.call2(hwEventAddr, rx.toLong(), len);
    }

    /**
     * A data frame that is neither IPv4 nor ARP ({@code eth}, {@code len} bytes from the Ethernet header): the
     * link's own business, if any -- on WiFi an EAPOL key frame (a WPA2 re-handshake must be answered, or the AP
     * deauthenticates us).
     */
    public static void linkFrame(Address eth, int len)
    {
        long u = Magic.call2(hwLinkFrameAddr, eth.toLong(), len);
    }
}
