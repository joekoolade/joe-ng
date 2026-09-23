/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-22
 */
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code javax.crypto.Mac} on the metal, over joe-ng's own {@code crypto.Hmac}.
 *
 * <p>THE POINT OF RUNNING THIS SOURCE ON A HOST JVM IS THAT IT IS A CONTROL: there the same calls reach the
 * JDK's own SunJCE Mac, so every arm below is an independent known answer. Only the provider NAME is
 * expected to differ, and that arm is counted separately so {@code failures=0} means the same thing in both
 * worlds.
 *
 * <p>The arms that DISCRIMINATE rather than merely exercise the API:
 * <ul>
 * <li>CLONE. A {@code Mac} whose SPI clones shallowly shares one engine with its copy, so both branches
 *     authenticate the interleaving of two messages and return MACs that are plausible, stable, equal to
 *     each other and wrong. Only feeding the two copies DIFFERENTLY and checking each against its own
 *     whole-message MAC can see it.
 * <li>REUSE after {@code doFinal}. The contract is that it resets under the same key; an implementation
 *     that did not would authenticate the concatenation of every message it was ever given.
 * <li>The UNINITIALIZED arms. {@code update}/{@code doFinal} before {@code init} must throw
 *     {@link IllegalStateException}, not quietly MAC under a zero key.
 * <li>An EMPTY key, which is mathematically fine for HMAC and which stock REJECTS -- so answering it would
 *     be a silent divergence from every other JVM rather than a missing feature.
 * </ul>
 */
public class MacProbe
{
    private static int fails = 0;
    /** Arms where joe-ng deliberately differs from stock: counted SEPARATELY so both worlds read 0 above. */
    private static int unmet = 0;

    private static void say(String what, Object got, Object want)
    {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok)
        {
            fails += 1;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + what + " = " + got + " (want " + want + ")");
    }

    private static void diverge(String what, Object got, Object wantHere, String onStock)
    {
        boolean ok = got == null ? wantHere == null : got.equals(wantHere);
        if (!ok)
        {
            unmet += 1;
        }
        System.out.println((ok ? "ok*  " : "diff ") + what + " = " + got + " (want here " + wantHere
                + "; a stock JVM says " + onStock + ")");
    }

    private static String hex(byte[] b)
    {
        String s = "";
        int i = 0;
        while (i < b.length)
        {
            int v = b[i] & 0xFF;
            s += "0123456789abcdef".charAt(v >>> 4);
            s += "0123456789abcdef".charAt(v & 0xF);
            i += 1;
        }
        return s;
    }

    private static byte[] rep(byte b, int n)
    {
        byte[] out = new byte[n];
        int i = 0;
        while (i < n)
        {
            out[i] = b;
            i += 1;
        }
        return out;
    }

    private static byte[] ascii(String s)
    {
        byte[] out = new byte[s.length()];
        int i = 0;
        while (i < s.length())
        {
            out[i] = (byte) s.charAt(i);
            i += 1;
        }
        return out;
    }

    private static Mac mac(String alg, byte[] key) throws Exception
    {
        Mac m = Mac.getInstance(alg);
        m.init(new SecretKeySpec(key, alg));
        return m;
    }

    public static void main(String[] args) throws Exception
    {
        // ---- KNOWN-ANSWER vectors through the stock API (RFC 2202 §2/§3, RFC 4231 §4.2) -------------
        byte[] k0b = rep((byte) 0x0b, 20);
        byte[] hi = ascii("Hi There");
        say("HmacMD5    (RFC 2202 #1)", hex(mac("HmacMD5", rep((byte) 0x0b, 16)).doFinal(hi)),
                "9294727a3638bb1c13f48ef8158bfc9d");
        say("HmacSHA1   (RFC 2202 #1)", hex(mac("HmacSHA1", k0b).doFinal(hi)),
                "b617318655057264e28bc0b6fb378c8ef146be00");
        say("HmacSHA224 (RFC 4231 #1)", hex(mac("HmacSHA224", k0b).doFinal(hi)),
                "896fb1128abbdf196832107cd49df33f47b4b1169912ba4f53684b22");
        say("HmacSHA256 (RFC 4231 #1)", hex(mac("HmacSHA256", k0b).doFinal(hi)),
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
        say("HmacSHA384 (RFC 4231 #1)", hex(mac("HmacSHA384", k0b).doFinal(hi)),
                "afd03944d84895626b0825f4ab46907f15f9dadbe4101ec682aa034c7cebc59cfaea9ea9076ede7f4af152e8b2f"
                        + "a9cb6");
        say("HmacSHA512 (RFC 4231 #1)", hex(mac("HmacSHA512", k0b).doFinal(hi)),
                "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cdedaa833b7d6b8a702038b274eaea"
                        + "3f4e4be9d914eeb61f1702e696c203a126854");

        // A key LONGER than the block, which turns on the hash-the-key branch (RFC 2202 #6).
        say("HmacSHA1 long key (RFC 2202 #6)",
                hex(mac("HmacSHA1", rep((byte) 0xaa, 80))
                        .doFinal(ascii("Test Using Larger Than Block-Size Key - Hash Key First"))),
                "aa4ae5e15272d00e95705637ce8a3b55ed402112");

        // ---- lengths -------------------------------------------------------------------------------
        say("getMacLength HmacMD5", Integer.valueOf(Mac.getInstance("HmacMD5").getMacLength()),
                Integer.valueOf(16));
        say("getMacLength HmacSHA1", Integer.valueOf(Mac.getInstance("HmacSHA1").getMacLength()),
                Integer.valueOf(20));
        say("getMacLength HmacSHA256", Integer.valueOf(Mac.getInstance("HmacSHA256").getMacLength()),
                Integer.valueOf(32));
        say("getMacLength HmacSHA512", Integer.valueOf(Mac.getInstance("HmacSHA512").getMacLength()),
                Integer.valueOf(64));

        // ---- streaming must equal one-shot ---------------------------------------------------------
        byte[] key = ascii("a key for streaming");
        byte[] msg = ascii("the quick brown fox jumps over the lazy dog, repeatedly and at length");
        String want = hex(mac("HmacSHA256", key).doFinal(msg));
        Mac bytewise = mac("HmacSHA256", key);
        for (int i = 0; i < msg.length; i++)
        {
            bytewise.update(msg[i]);
        }
        say("byte-at-a-time == one-shot", hex(bytewise.doFinal()), want);

        Mac ragged = mac("HmacSHA256", key);
        ragged.update(msg, 0, 7);
        ragged.update(msg, 7, 31);
        ragged.update(msg, 38, msg.length - 38);
        say("ragged chunks == one-shot", hex(ragged.doFinal()), want);

        // ---- doFinal RESETS under the same key -----------------------------------------------------
        Mac reused = mac("HmacSHA256", key);
        reused.update(msg, 0, msg.length);
        reused.doFinal();
        reused.update(msg, 0, msg.length);
        say("doFinal resets (2nd == 1st)", hex(reused.doFinal()), want);

        // ---- reset() discards fed input but keeps the key ------------------------------------------
        Mac resetme = mac("HmacSHA256", key);
        resetme.update(ascii("rubbish that must be discarded"));
        resetme.reset();
        resetme.update(msg, 0, msg.length);
        say("reset() keeps the key", hex(resetme.doFinal()), want);

        // ---- CLONE must be independent -------------------------------------------------------------
        byte[] head = ascii("common prefix ");
        byte[] tailA = ascii("branch A");
        byte[] tailB = ascii("a much longer branch B");
        Mac base = mac("HmacSHA256", key);
        base.update(head, 0, head.length);
        Mac forked = (Mac) base.clone();
        base.update(tailA, 0, tailA.length);
        forked.update(tailB, 0, tailB.length);
        byte[] wholeA = new byte[head.length + tailA.length];
        byte[] wholeB = new byte[head.length + tailB.length];
        System.arraycopy(head, 0, wholeA, 0, head.length);
        System.arraycopy(tailA, 0, wholeA, head.length, tailA.length);
        System.arraycopy(head, 0, wholeB, 0, head.length);
        System.arraycopy(tailB, 0, wholeB, head.length, tailB.length);
        say("clone: original branch", hex(base.doFinal()), hex(mac("HmacSHA256", key).doFinal(wholeA)));
        say("clone: forked branch", hex(forked.doFinal()), hex(mac("HmacSHA256", key).doFinal(wholeB)));

        // ---- ByteBuffer ----------------------------------------------------------------------------
        Mac viaBuf = mac("HmacSHA256", key);
        ByteBuffer bb = ByteBuffer.wrap(msg);
        viaBuf.update(bb);
        say("update(ByteBuffer) == one-shot", hex(viaBuf.doFinal()), want);
        say("update(ByteBuffer) drained it", Integer.valueOf(bb.remaining()), Integer.valueOf(0));

        // ---- doFinal into a caller's array ---------------------------------------------------------
        byte[] into = new byte[40];
        Mac out = mac("HmacSHA256", key);
        out.update(msg, 0, msg.length);
        out.doFinal(into, 8);
        byte[] slice = new byte[32];
        System.arraycopy(into, 8, slice, 0, 32);
        say("doFinal(byte[],int) at an offset", hex(slice), want);

        boolean shortBuf = false;
        try
        {
            Mac m = mac("HmacSHA256", key);
            m.update(msg, 0, msg.length);
            m.doFinal(new byte[31], 0);
        }
        catch (ShortBufferException e)
        {
            shortBuf = true;
        }
        say("doFinal into a short buffer throws", Boolean.valueOf(shortBuf), Boolean.TRUE);

        // ---- the name is the REQUESTED spelling, and lookup is case-insensitive ---------------------
        say("getAlgorithm keeps the spelling", Mac.getInstance("hmacsha256").getAlgorithm(), "hmacsha256");
        say("case-insensitive lookup works",
                hex(mac("HMACSHA256", key).doFinal(msg)), want);

        // ---- refusals ------------------------------------------------------------------------------
        boolean nsae = false;
        try
        {
            Mac.getInstance("HmacSHA3-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            nsae = true;
        }
        // A HOST CONTROL corrected this arm before it shipped: a stock JVM DOES carry HmacSHA3-256, so
        // "is refused" is joe-ng's answer, not a universal one. Written as say() it would have read as a
        // regression on every host run -- the same trap SecureRandomProbe hit, where the control refused
        // two arms and one of them tested a member that does not exist.
        diverge("HmacSHA3-256", nsae ? "NoSuchAlgorithmException" : "an instance",
                "NoSuchAlgorithmException", "an instance (SHA-3 is a sponge, not a parameter of SHA-2)");

        boolean truncRefused = false;
        try
        {
            Mac.getInstance("HmacSHA512/256");
        }
        catch (NoSuchAlgorithmException e)
        {
            truncRefused = true;
        }
        diverge("HmacSHA512/256", truncRefused ? "NoSuchAlgorithmException" : "an instance",
                "NoSuchAlgorithmException", "an instance (a truncated variant is a different MAC)");

        boolean uninit = false;
        try
        {
            Mac.getInstance("HmacSHA256").update((byte) 1);
        }
        catch (IllegalStateException e)
        {
            uninit = true;
        }
        say("update before init throws", Boolean.valueOf(uninit), Boolean.TRUE);

        boolean uninitFinal = false;
        try
        {
            Mac.getInstance("HmacSHA256").doFinal();
        }
        catch (IllegalStateException e)
        {
            uninitFinal = true;
        }
        say("doFinal before init throws", Boolean.valueOf(uninitFinal), Boolean.TRUE);

        boolean emptyKey = false;
        try
        {
            new SecretKeySpec(new byte[0], "HmacSHA256");
        }
        catch (IllegalArgumentException e)
        {
            emptyKey = true;
        }
        say("an EMPTY key is refused at construction", Boolean.valueOf(emptyKey), Boolean.TRUE);

        boolean nullKey = false;
        try
        {
            Mac.getInstance("HmacSHA256").init(null);
        }
        catch (InvalidKeyException e)
        {
            nullKey = true;
        }
        say("init(null) throws InvalidKeyException", Boolean.valueOf(nullKey), Boolean.TRUE);

        // ---- SecretKeySpec itself ------------------------------------------------------------------
        SecretKeySpec sk = new SecretKeySpec(key, "HmacSHA256");
        say("SecretKeySpec format", sk.getFormat(), "RAW");
        say("SecretKeySpec algorithm", sk.getAlgorithm(), "HmacSHA256");
        say("SecretKeySpec encoded round-trips", hex(sk.getEncoded()), hex(key));
        byte[] taken = sk.getEncoded();
        taken[0] = (byte) ~taken[0];
        say("getEncoded hands out a COPY", hex(sk.getEncoded()), hex(key));
        say("equals: same bytes+algorithm", Boolean.valueOf(sk.equals(new SecretKeySpec(key, "HMACSHA256"))),
                Boolean.TRUE);
        say("equals: different bytes", Boolean.valueOf(sk.equals(new SecretKeySpec(ascii("other"),
                "HmacSHA256"))), Boolean.FALSE);

        // ---- the one deliberate divergence ---------------------------------------------------------
        diverge("provider", Mac.getInstance("HmacSHA256").getProvider().getName(), "joe-ng", "SunJCE");

        System.out.println("MacProbe done, failures=" + fails + " divergences-unmet=" + unmet);
    }
}
