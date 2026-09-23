/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-23
 */
import java.security.NoSuchAlgorithmException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code javax.crypto.SecretKeyFactory} on the metal, over joe-ng's own {@code crypto.Pbkdf2}.
 *
 * <p><b>THE SAME SOURCE IS THE ORACLE.</b> Run on a host JVM every call below reaches the JDK's own SunJCE
 * factory, so each expected value is an independent known answer; run on metal they reach the overlay. The
 * hex must come out byte-identical in both worlds.
 *
 * <p>The arms that DISCRIMINATE, as opposed to merely exercising the API:
 * <ul>
 * <li>The three PASSWORD ENCODING arms. Latin-1 is the tempting encoding for a {@code char[]} and produces
 *     a perfectly good key that agrees with nothing; only the published hex separates it from UTF-8. The
 *     SURROGATE PAIR arm exercises the four-byte path, and the UNPAIRED surrogate must equal the
 *     {@code '?'} answer -- which is how {@code Charset.encode}'s REPLACE action shows itself.
 * <li>The TRUNCATION arms. {@code keyLength} is in BITS: 255 must yield the 256-bit answer's first 31
 *     bytes, so an implementation that ROUNDED UP rather than truncating, or that mistook bits for bytes,
 *     fails here and nowhere else.
 * <li>The five ALGORITHM arms, because SHA-224 and SHA-384 are the same compression function as their
 *     siblings under different initial chaining values -- an implementation that truncated SHA-256/512
 *     would return the right LENGTH and be wrong in every byte.
 * </ul>
 */
public class SecretKeyFactoryProbe
{
    private static int fails = 0;
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

    /** An arm where joe-ng deliberately differs from stock; counted apart so `failures` reads 0 in both. */
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
        for (int i = 0; i < b.length; i++)
        {
            int v = b[i] & 0xFF;
            s += "0123456789abcdef".charAt(v >>> 4);
            s += "0123456789abcdef".charAt(v & 0xF);
        }
        return s;
    }

    private static byte[] ascii(String s)
    {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++)
        {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /** One derivation, as hex. */
    private static String key(String alg, char[] pw, String salt, int iters, int bits) throws Exception
    {
        SecretKey k = SecretKeyFactory.getInstance(alg)
                .generateSecret(new PBEKeySpec(pw, ascii(salt), iters, bits));
        return hex(k.getEncoded());
    }

    public static void main(String[] args) throws Exception
    {
        // ---- published vectors through the STOCK API -------------------------------------------------
        // RFC 6070 (PBKDF2-HMAC-SHA1) and RFC 7914 section 11 (PBKDF2-HMAC-SHA256).
        say("RFC6070 sha1(password,salt,1,160)",
                key("PBKDF2WithHmacSHA1", "password".toCharArray(), "salt", 1, 160),
                "0c60c80f961f0e71f3a9b524af6012062fe037a6");
        say("RFC6070 sha1(password,salt,4096,160)",
                key("PBKDF2WithHmacSHA1", "password".toCharArray(), "salt", 4096, 160),
                "4b007901b765489abead49d926f721d065a429c1");
        say("RFC7914 sha256(passwd,salt,1,512)",
                key("PBKDF2WithHmacSHA256", "passwd".toCharArray(), "salt", 1, 512),
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
                        + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783");

        // ---- every algorithm, against the JDK's own answers ------------------------------------------
        say("sha224(password,salt,2,160)",
                key("PBKDF2WithHmacSHA224", "password".toCharArray(), "salt", 2, 160),
                "93200ffa96c5776d38fa10abdf8f5bfc0054b971");
        say("sha256(p,salt,1,256)",
                key("PBKDF2WithHmacSHA256", "p".toCharArray(), "salt", 1, 256),
                "81152884dd9a208f9be68dc312d994c096fd10c84d8cf56a11995f7c604bc2b2");
        say("sha384(password,salt,2,160)",
                key("PBKDF2WithHmacSHA384", "password".toCharArray(), "salt", 2, 160),
                "54f775c6d790f21930459162fc535dbf04a93918");
        say("sha512(password,salt,2,160)",
                key("PBKDF2WithHmacSHA512", "password".toCharArray(), "salt", 2, 160),
                "e1d9c16aa681708a45f5c7c4e215ceb66e011a2e");

        // ---- the password is UTF-8, which Latin-1 and UTF-16 both fail --------------------------------
        say("pw U+00E9 is UTF-8 (2 bytes)",
                key("PBKDF2WithHmacSHA256", new char[] { 'é' }, "salt", 1, 256),
                "4cd20bd84fa718e51b66e551c92755565f5283d9196563b0362a3623fcf4a460");
        say("pw U+20AC is UTF-8 (3 bytes)",
                key("PBKDF2WithHmacSHA256", new char[] { '€' }, "salt", 1, 256),
                "80b010b02d0f003607289e4e5c59178bc17433896c9ae03ab5b79a905a80afcc");
        say("pw U+1F600 surrogate PAIR is one 4-byte sequence",
                key("PBKDF2WithHmacSHA256", new char[] { '\ud83d', '\ude00' }, "salt", 1, 256),
                "b14ba8f75252246352a8bbdcddde461e7adcbd724825d9d2e5d940a1c74ce3b6");
        // The REPLACE action, shown rather than asserted as a constant: an unpaired surrogate must produce
        // EXACTLY the answer for '?', which is what Charset.encode substitutes.
        say("unpaired surrogate == '?'",
                key("PBKDF2WithHmacSHA256", new char[] { '\ud83d' }, "salt", 1, 256),
                key("PBKDF2WithHmacSHA256", new char[] { '?' }, "salt", 1, 256));
        say("empty password is legal",
                key("PBKDF2WithHmacSHA256", new char[0], "salt", 1, 128),
                "f135c27993baf98773c5cdb40a5706ce");

        // ---- keyLength is BITS, truncated by integer division -----------------------------------------
        say("keyLength 256 bits -> 32 bytes",
                Integer.valueOf(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(new PBEKeySpec("p".toCharArray(), ascii("salt"), 1, 256))
                        .getEncoded().length), Integer.valueOf(32));
        say("keyLength 255 bits -> 31 bytes, the SAME first 31",
                key("PBKDF2WithHmacSHA256", "p".toCharArray(), "salt", 1, 255),
                "81152884dd9a208f9be68dc312d994c096fd10c84d8cf56a11995f7c604bc2");
        say("keyLength 9 bits -> 1 byte",
                Integer.valueOf(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(new PBEKeySpec("p".toCharArray(), ascii("salt"), 1, 9))
                        .getEncoded().length), Integer.valueOf(1));

        // ---- the key object ---------------------------------------------------------------------------
        SecretKey k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec("pw".toCharArray(), ascii("salt"), 2, 256));
        say("key.getFormat", k.getFormat(), "RAW");
        say("key.getAlgorithm", k.getAlgorithm(), "PBKDF2WithHmacSHA256");
        say("key equals a SecretKeySpec of the same bytes",
                Boolean.valueOf(k.equals(new SecretKeySpec(k.getEncoded(), "PBKDF2WithHmacSHA256"))),
                Boolean.TRUE);

        // ---- lookup -----------------------------------------------------------------------------------
        say("getAlgorithm keeps the REQUESTED spelling",
                SecretKeyFactory.getInstance("pbkdf2withhmacsha256").getAlgorithm(),
                "pbkdf2withhmacsha256");
        say("lowercase lookup derives the same key",
                key("pbkdf2withhmacsha256", "p".toCharArray(), "salt", 1, 256),
                "81152884dd9a208f9be68dc312d994c096fd10c84d8cf56a11995f7c604bc2b2");

        String noAlg = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacMD5");
        }
        catch (NoSuchAlgorithmException e)
        {
            noAlg = "NSAE";
        }
        // Stock ships no PBKDF2WithHmacMD5 either -- this is a MATCH, not a divergence.
        say("PBKDF2WithHmacMD5 is refused (stock has none either)", noAlg, "NSAE");

        String nullAlg = "none";
        try
        {
            SecretKeyFactory.getInstance((String) null);
        }
        catch (NullPointerException e)
        {
            nullAlg = "NPE";
        }
        say("getInstance(null)", nullAlg, "NPE");

        // ---- PBEKeySpec validates in its CONSTRUCTOR, not in generateSecret ---------------------------
        say("empty salt", specThrows(new byte[0], 1, 256), "IllegalArgumentException");
        say("zero iterations", specThrows(ascii("salt"), 0, 256), "IllegalArgumentException");
        say("zero keyLength", specThrows(ascii("salt"), 1, 0), "IllegalArgumentException");
        say("negative iterations", specThrows(ascii("salt"), -1, 256), "IllegalArgumentException");

        String nullSalt = "none";
        try
        {
            new PBEKeySpec("p".toCharArray(), null, 1, 256);
        }
        catch (NullPointerException e)
        {
            nullSalt = "NullPointerException";
        }
        say("null salt", nullSalt, "NullPointerException");

        // ---- PBEKeySpec's own accessors ---------------------------------------------------------------
        PBEKeySpec spec = new PBEKeySpec("secret".toCharArray(), ascii("NaCl"), 7, 128);
        say("spec.getIterationCount", Integer.valueOf(spec.getIterationCount()), Integer.valueOf(7));
        say("spec.getKeyLength", Integer.valueOf(spec.getKeyLength()), Integer.valueOf(128));
        say("spec.getSalt", hex(spec.getSalt()), hex(ascii("NaCl")));
        say("spec.getPassword round-trips", new String(spec.getPassword()), "secret");
        // getPassword CLONES, so mutating what it returns must not change the spec.
        char[] copy = spec.getPassword();
        copy[0] = 'X';
        say("spec.getPassword returns a COPY", new String(spec.getPassword()), "secret");
        spec.clearPassword();
        String cleared = "none";
        try
        {
            spec.getPassword();
        }
        catch (IllegalStateException e)
        {
            cleared = "IllegalStateException";
        }
        say("getPassword after clearPassword", cleared, "IllegalStateException");

        // ---- generateSecret rejects a spec it cannot use ----------------------------------------------
        say("generateSecret(SecretKeySpec)",
                genThrows(new SecretKeySpec(new byte[16], "x")), "InvalidKeySpecException");
        say("generateSecret(null)", genThrows(null), "InvalidKeySpecException");

        // ---- translateKey ------------------------------------------------------------------------------
        say("translateKey(our own key) keeps the bytes",
                hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").translateKey(k).getEncoded()),
                hex(k.getEncoded()));

        String badKey = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .translateKey(new SecretKeySpec(new byte[16], "AES"));
        }
        catch (java.security.InvalidKeyException e)
        {
            badKey = "InvalidKeyException";
        }
        say("translateKey(a key of another algorithm)", badKey, "InvalidKeyException");

        // ---- STATED DIVERGENCES ------------------------------------------------------------------------
        diverge("provider",
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getProvider().getName(),
                "joe-ng", "SunJCE");

        String t224 = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512/224");
        }
        catch (NoSuchAlgorithmException e)
        {
            t224 = "NSAE";
        }
        diverge("PBKDF2WithHmacSHA512/224", t224, "NSAE",
                "an instance -- crypto.Digest has no truncated SHA-512");

        String t256 = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512/256");
        }
        catch (NoSuchAlgorithmException e)
        {
            t256 = "NSAE";
        }
        diverge("PBKDF2WithHmacSHA512/256", t256, "NSAE",
                "an instance -- crypto.Digest has no truncated SHA-512");

        String spec7 = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec("p".toCharArray(), ascii("salt"), 1, 7));
        }
        catch (java.security.spec.InvalidKeySpecException e)
        {
            spec7 = "InvalidKeySpecException";
        }
        diverge("keyLength 7 bits (under one byte)", spec7, "InvalidKeySpecException",
                "a ZERO-LENGTH key, silently");

        String gks = "none";
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").getKeySpec(k, PBEKeySpec.class);
        }
        catch (java.security.spec.InvalidKeySpecException e)
        {
            gks = "InvalidKeySpecException";
        }
        diverge("getKeySpec(key, PBEKeySpec.class)", gks, "InvalidKeySpecException",
                "a PBEKeySpec -- its key RETAINS the password");

        System.out.println("SecretKeyFactoryProbe done, failures=" + fails + " divergences-unmet=" + unmet);
    }

    /** {@return the simple name of what the PBEKeySpec constructor threw, or "none"} */
    private static String specThrows(byte[] salt, int iters, int bits)
    {
        try
        {
            new PBEKeySpec("p".toCharArray(), salt, iters, bits);
            return "none";
        }
        catch (IllegalArgumentException e)
        {
            return "IllegalArgumentException";
        }
    }

    /** {@return the simple name of what generateSecret threw, or "none"} */
    private static String genThrows(java.security.spec.KeySpec spec)
    {
        try
        {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec);
            return "none";
        }
        catch (java.security.spec.InvalidKeySpecException e)
        {
            return "InvalidKeySpecException";
        }
        catch (NoSuchAlgorithmException e)
        {
            return "NoSuchAlgorithmException";
        }
    }
}
