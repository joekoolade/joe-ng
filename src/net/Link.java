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

/**
 * The network interface as the stack sees it: send an 802.3 frame, wait for one, and -- for a frame the link
 * received -- say which channel it came on and where its Ethernet frame starts. Everything above this (IPv4, ARP,
 * TCP, DHCP, DNS, the receive queues) works on Ethernet frames and asks here; nothing above parses a link's own
 * framing.
 *
 * <p>A received frame is kept exactly as the link delivered it. The Pi's CYW43455 wraps each one in an SDPCM header
 * and a BDC header (and also delivers control replies and firmware events that are not Ethernet at all), so
 * {@link #ethOffset} is where that envelope is unwrapped -- the same arithmetic {@code Ip}, {@code RxQueues} and
 * {@code NetStats} each used to do inline.
 */
public final class Link
{
    private Link()
    {
    }

    /** How long one receive wait sleeps before answering "nothing yet" (callers loop on 0). */
    public static final int RX_WAIT_MS = 200;

    /** A received frame's channel: a data (Ethernet) frame, a control reply, a link event, or something else. */
    public static final int CH_DATA = 0;
    public static final int CH_CTRL = 1;
    public static final int CH_EVENT = 2;
    public static final int CH_OTHER = 3;

    /** Send a fully built 802.3 frame. */
    public static void send(Address frame, int len)
    {
        board.cyw43.Cyw43.txData(frame, len);
    }

    /** Our 6-byte MAC (heap address); the link reads it from the hardware the first time. */
    public static Address mac()
    {
        return board.cyw43.Cyw43.mac();
    }

    /**
     * Wait up to {@code ms} for the next frame straight off the link (before {@link RxQueues} is active, while the
     * caller is the only reader), as the link delivered it. Its length, or 0.
     */
    public static int waitFrame(Address dst, int cap, int ms)
    {
        return board.cyw43.Cyw43.waitFrameIrq(dst, cap, ms);
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
        if (len < 12)
        {
            return CH_OTHER;
        }
        int ch = rx.plus(5).loadUnsignedByte() & 0x0F;     // SDPCM channel: 0 control, 1 event, 2 data
        if (ch == 0)
        {
            return CH_CTRL;
        }
        if (ch == 1)
        {
            return CH_EVENT;
        }
        if (ch == 2)
        {
            return CH_DATA;
        }
        return CH_OTHER;
    }

    /**
     * Where the Ethernet frame starts inside the received frame at {@code rx} ({@code len} bytes), or -1 when it is
     * not a data frame. On the CYW43455: past the SDPCM header (its data-offset byte) and the 4-byte BDC header and
     * its padding words.
     */
    public static int ethOffset(Address rx, int len)
    {
        if (channel(rx, len) != CH_DATA)
        {
            return -1;
        }
        int doff = rx.plus(7).loadUnsignedByte() & 0xFF;   // SDPCM data offset
        Address bdc = rx.plus(doff);
        return doff + 4 + (bdc.plus(3).loadUnsignedByte() & 0xFF) * 4;
    }

    /** A link event (CH_EVENT) the queues have no consumer for: the link logs it. */
    public static void event(Address rx, int len)
    {
        board.cyw43.Cyw43.logRxEvent(rx, len);
    }

    /**
     * A data frame that is neither IPv4 nor ARP ({@code eth}, {@code len} bytes from the Ethernet header): the
     * link's own business, if any -- on WiFi an EAPOL key frame (a WPA2 re-handshake must be answered, or the AP
     * deauthenticates us).
     */
    public static void linkFrame(Address eth, int len)
    {
        int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
        if (et == 0x888E)
        {
            board.cyw43.Supplicant.input(eth, len);
        }
    }
}
