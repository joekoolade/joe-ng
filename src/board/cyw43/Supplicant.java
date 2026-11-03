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

/**
 * The host WPA2-PSK supplicant after bring-up. {@link Cyw43#fourWay} runs the first 4-way synchronously;
 * from then on every EAPOL-Key frame arrives on the RX task ({@link net.RxQueues#dispatch}) and is answered
 * here, because the AP re-runs the handshake whenever it likes (a PTK rekey, or after the firmware
 * re-associates by itself) and deauths us with reason 15 if nobody answers -- Pi run 13 looped on exactly
 * that. Three messages are handled:
 * <ul>
 * <li>pairwise msg1 (Ack, no MIC): new SNonce, derive a PTK, send msg2;</li>
 * <li>pairwise msg3 (Ack, MIC, Install): verify the MIC with that PTK, unwrap the GTK, send msg4, install
 *     the TK and the GTK;</li>
 * <li>group msg1 (Ack, MIC, Secure, not pairwise): verify with the current PTK, unwrap the GTK, send group
 *     msg2, install the GTK.</li>
 * </ul>
 * Everything runs on the RX task, so the state needs no lock. Key installs do not wait for the ioctl ack
 * (the RX task is the one that would read it).
 */
public final class Supplicant
{
    private Supplicant()
    {
    }

    private static final int KI_PAIRWISE = 0x0008;
    private static final int KI_ACK = 0x0080;
    private static final int KI_MIC = 0x0100;
    private static final int KI_SECURE = 0x0200;
    private static final int KI_MSG2 = 0x010a;           // MIC | pairwise | HMAC-SHA1/AES (ver 2)
    private static final int KI_MSG4 = 0x030a;           // + Secure
    private static final int KI_GROUP2 = 0x0302;         // MIC | Secure | ver 2

    // What the supplicant MEASURED.
    public static int frames;                            // EAPOL frames received after bring-up
    public static int rekeys;                            // completed re-handshakes (pairwise msg4 sent)
    public static int groupRekeys;                       // completed group-key handshakes
    public static int rejected;                          // MIC/replay/unwrap/format failures

    private static boolean ready;                        // the bring-up 4-way finished
    private static byte[] ie;                            // msg2 key data: the RSN IE the association used
    private static int ieLen;
    private static byte[] kck;                           // current (installed) PTK halves
    private static byte[] kek;
    private static byte[] replay;                        // last MIC-verified replay counter
    private static byte[] pendKck;                       // PTK derived from the last msg1, not yet confirmed
    private static byte[] pendKek;
    private static byte[] pendTk;
    private static int pendKeyLen;
    private static byte[] sNonce;                        // kept across retransmitted msg1s (as the bring-up
                                                         //   4-way does), dropped once msg3 completes
    private static byte[] pendReplay;                    // msg1's counter: msg3 must exceed it (a new
                                                         //   association may restart the AP's counter)

    /** The bring-up 4-way succeeded: remember the IE and the PTK halves for later rekeys. */
    static void established(byte[] rsnIe, int rsnIeLen, byte[] ck, byte[] ek)
    {
        ie = Cyw43.slice(rsnIe, 0, rsnIeLen);
        ieLen = rsnIeLen;
        kck = ck;
        kek = ek;
        replay = new byte[8];
        ready = true;
    }

    /**
     * An EAPOL frame on the RX task. {@code eth} is the 802.3 header, {@code len} the bytes from it to the
     * end of the frame.
     */
    public static void input(long eth, int len)
    {
        frames = frames + 1;
        if (!ready || len < 14 + 99)
        {
            return;
        }
        long body = eth + 14;
        if ((Magic.load8(body + 1) & 0xFF) != 3)        // not EAPOL-Key
        {
            return;
        }
        int eapLen = ((Magic.load8(body + 2) & 0xFF) << 8) | (Magic.load8(body + 3) & 0xFF);
        if (eapLen < 95 || 4 + eapLen > len - 14)
        {
            reject(Magic.bytes("wifi: rekey short frame\n"));
            return;
        }
        int ki = ((Magic.load8(body + 5) & 0xFF) << 8) | (Magic.load8(body + 6) & 0xFF);
        byte[] aa = Cyw43.heapBytes(eth + 6, 6);         // the AP's MAC (Ethernet source)
        if ((ki & KI_ACK) == 0)
        {
            return;                                      // not from the authenticator
        }
        if ((ki & KI_PAIRWISE) != 0 && (ki & KI_MIC) == 0)
        {
            msg1(body, aa);
        }
        else if ((ki & KI_PAIRWISE) != 0)
        {
            msg3(body, eapLen, aa);
        }
        else if ((ki & (KI_MIC | KI_SECURE)) == (KI_MIC | KI_SECURE))
        {
            groupMsg1(body, eapLen, aa);
        }
    }

    /** Pairwise msg1: derive a PTK from its ANonce and our SNonce, send msg2. */
    private static void msg1(long body, byte[] aa)
    {
        byte[] aNonce = Cyw43.heapBytes(body + 17, 32);
        byte[] r = Cyw43.heapBytes(body + 9, 8);
        pendReplay = r;
        pendKeyLen = ((Magic.load8(body + 7) & 0xFF) << 8) | (Magic.load8(body + 8) & 0xFF);
        if (sNonce == null)
        {
            sNonce = new byte[32];
            Cyw43.genNonce(sNonce);
        }
        byte[] ptk = new byte[48];
        Cyw43.derivePtk(Cyw43.staPmk, aa, Cyw43.heapBytes(Cyw43.ourMac, 6), aNonce, sNonce, ptk);
        pendKck = Cyw43.slice(ptk, 0, 16);
        pendKek = Cyw43.slice(ptk, 16, 16);
        pendTk = Cyw43.slice(ptk, 32, 16);
        Cyw43.sendEapol(aa, KI_MSG2, r, pendKeyLen, sNonce, ie, ieLen, pendKck);
        board.bcm2711.Uart.write(Magic.bytes("wifi: rekey msg1 -> msg2 sent\n"));
    }

    /** Pairwise msg3: verify with the pending PTK, unwrap the GTK, send msg4, install TK + GTK. */
    private static void msg3(long body, int eapLen, byte[] aa)
    {
        if (pendKck == null)
        {
            reject(Magic.bytes("wifi: rekey msg3 without msg1\n"));
            return;
        }
        if (!micOk(body, eapLen, pendKck) || !fresh(body, pendReplay))
        {
            reject(Magic.bytes("wifi: rekey msg3 MIC/replay bad\n"));
            return;
        }
        byte[] gtk = new byte[16];
        int gtkId = unwrapGtk(body, pendKek, gtk);
        if (gtkId < 0)
        {
            reject(Magic.bytes("wifi: rekey msg3 no GTK\n"));
            return;
        }
        replay = Cyw43.heapBytes(body + 9, 8);
        Cyw43.sendEapol(aa, KI_MSG4, replay, pendKeyLen, new byte[32], new byte[0], 0, pendKck);
        Cyw43.installKey(0, pendTk, 16, aa, true);
        Cyw43.installKey(gtkId, gtk, 16, new byte[6], false);
        kck = pendKck;
        kek = pendKek;
        pendKck = null;
        pendKek = null;
        pendTk = null;
        pendReplay = null;
        sNonce = null;
        rekeys = rekeys + 1;
        board.bcm2711.Uart.write(Magic.bytes("wifi: rekey msg3 ok -> msg4 sent, keys installed n="));
        VM.printDec(rekeys);
        board.bcm2711.Uart.putc(0x0A);
    }

    /** Group msg1: verify with the current PTK, unwrap the new GTK, send group msg2, install the GTK. */
    private static void groupMsg1(long body, int eapLen, byte[] aa)
    {
        if (!micOk(body, eapLen, kck) || !fresh(body, replay))
        {
            reject(Magic.bytes("wifi: group rekey MIC/replay bad\n"));
            return;
        }
        byte[] gtk = new byte[16];
        int gtkId = unwrapGtk(body, kek, gtk);
        if (gtkId < 0)
        {
            reject(Magic.bytes("wifi: group rekey no GTK\n"));
            return;
        }
        replay = Cyw43.heapBytes(body + 9, 8);
        Cyw43.sendEapol(aa, KI_GROUP2, replay, 0, new byte[32], new byte[0], 0, kck);
        Cyw43.installKey(gtkId, gtk, 16, new byte[6], false);
        groupRekeys = groupRekeys + 1;
        board.bcm2711.Uart.write(Magic.bytes("wifi: group rekey -> msg2 sent, GTK installed n="));
        VM.printDec(groupRekeys);
        board.bcm2711.Uart.putc(0x0A);
    }

    /** HMAC-SHA1-128 over the EAPOL frame (MIC field zeroed) equals the frame's MIC. */
    private static boolean micOk(long body, int eapLen, byte[] ck)
    {
        byte[] frame = Cyw43.heapBytes(body, 4 + eapLen);
        byte[] rxMic = Cyw43.slice(frame, 81, 16);
        int i = 0;
        while (i < 16)
        {
            frame[81 + i] = 0;
            i = i + 1;
        }
        byte[] calc = new byte[20];
        crypto.Hmac.mac(crypto.Digest.SHA1, ck, 16, frame, frame.length, calc);
        return Cyw43.cmp(calc, rxMic, 16) == 0;
    }

    /** The frame's replay counter is strictly greater than {@code base} (big-endian, unsigned). */
    private static boolean fresh(long body, byte[] base)
    {
        byte[] r = Cyw43.heapBytes(body + 9, 8);
        int i = 0;
        while (i < 8)
        {
            int a = r[i] & 0xFF;
            int b = base[i] & 0xFF;
            if (a != b)
            {
                return a > b;
            }
            i = i + 1;
        }
        return false;
    }

    /** AES-unwrap the key data with {@code ek} and pull out the GTK KDE; its key id, or -1. */
    private static int unwrapGtk(long body, byte[] ek, byte[] gtk)
    {
        int kdLen = ((Magic.load8(body + 97) & 0xFF) << 8) | (Magic.load8(body + 98) & 0xFF);
        if (kdLen < 16 || (kdLen & 7) != 0)
        {
            return -1;
        }
        byte[] wrapped = Cyw43.heapBytes(body + 99, kdLen);
        byte[] kd = new byte[kdLen - 8];
        if (!crypto.KeyWrap.unwrap(ek, wrapped, kdLen, kd))
        {
            return -1;
        }
        return Cyw43.extractGtk(kd, kd.length, gtk);
    }

    private static void reject(byte[] why)
    {
        rejected = rejected + 1;
        board.bcm2711.Uart.write(why);
    }
}
