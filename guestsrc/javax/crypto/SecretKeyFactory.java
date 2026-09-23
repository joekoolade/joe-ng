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
package javax.crypto;

import crypto.Digest;
import crypto.Pbkdf2;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code javax.crypto.SecretKeyFactory} over joe-ng's own {@link Pbkdf2}: PBKDF2 with HMAC-SHA1/224/256/
 * 384/512.
 *
 * <p><b>OVERLAID FOR A MEASURED REASON, and it is the only class of this trio that needs to be.</b> Stock
 * {@code SecretKeyFactory} imports {@code sun.security.jca.*} and resolves through {@code GetInstance} to a
 * {@code Provider} registry read from a properties FILE, populated by {@code ServiceLoader} over the module
 * graph and instantiated reflectively through {@code java.lang.invoke} -- and then {@code JceSecurity}
 * VERIFIES THE PROVIDER'S JAR SIGNATURE, which is the whole of why {@code sun/security/} is denied here. No
 * faithful copy could work whatever its shape. Same stated exception {@code MessageDigest}, {@code Mac} and
 * {@code ServiceLoader} already take.
 *
 * <p><b>Its two companions are NOT overlaid, which was checked rather than assumed.</b>
 * {@code SecretKeyFactorySpi} is a constructor and three abstract methods; {@code PBEKeySpec} imports only
 * {@code KeySpec} and {@code Arrays} and is a data holder. Neither needs a native or a subsystem this VM
 * lacks, so both load STOCK -- the shape the permission layer already uses, and a narrower overlay surface
 * than {@code Mac} needed.
 *
 * <p><b>Two semantics here were MEASURED against a host JVM rather than recalled, and both would have been
 * silently wrong:</b>
 * <ul>
 * <li>The {@code char[]} password is encoded <b>UTF-8</b>. Stock is
 *     {@code UTF_8.encode(CharBuffer.wrap(passwd))}; a host control put the UTF-8, Latin-1 and UTF-16 arms
 *     side by side and only UTF-8 reproduced the JDK's answer. Latin-1 is the tempting one -- it is what
 *     "one char, one byte" suggests -- and it produces a perfectly good key that agrees with nothing.
 * <li>{@code PBEKeySpec}'s {@code keyLength} is in <b>BITS, truncated by integer division</b>. Measured: 7
 *     bits yields a ZERO-LENGTH key on stock without throwing, 9 bits yields 1 byte, and 255 bits yields
 *     the 256-bit answer's first 31 bytes.
 * </ul>
 */
public class SecretKeyFactory
{
    private final SecretKeyFactorySpi spi;
    private final Provider provider;
    private final String algorithm;

    /** Stock's protected constructor, kept so a caller that subclasses this works unchanged. */
    protected SecretKeyFactory(SecretKeyFactorySpi keyFacSpi, Provider provider, String algorithm)
    {
        this.spi = keyFacSpi;
        this.provider = provider;
        this.algorithm = algorithm;
    }

    /**
     * {@return a factory for {@code algorithm}}
     *
     * @throws NoSuchAlgorithmException if this VM has no such algorithm
     * @throws NullPointerException     if {@code algorithm} is null, which is what stock throws
     */
    public static final SecretKeyFactory getInstance(String algorithm) throws NoSuchAlgorithmException
    {
        if (algorithm == null)
        {
            throw new NullPointerException("null algorithm name");
        }
        int id = digestIdFor(algorithm);
        if (id < 0)
        {
            throw new NoSuchAlgorithmException(algorithm + " SecretKeyFactory not available");
        }
        // The REQUESTED spelling is what getAlgorithm() reports -- MEASURED: a lowercase request comes back
        // lowercase from a host JVM. Canonicalising was the MessageDigest overlay's first bug.
        return new SecretKeyFactory(new Impl(id, algorithm), Provider.JOENG, algorithm);
    }

    /** {@return a factory for {@code algorithm}} joe-ng has ONE provider, so the name is checked and ignored. */
    public static final SecretKeyFactory getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException
    {
        return getInstance(algorithm);
    }

    /** {@return a factory for {@code algorithm}} joe-ng has ONE provider, so the argument is ignored. */
    public static final SecretKeyFactory getInstance(String algorithm, Provider provider)
            throws NoSuchAlgorithmException
    {
        return getInstance(algorithm);
    }

    /** {@return the provider} */
    public final Provider getProvider()
    {
        return provider;
    }

    /** {@return the algorithm name, in the SPELLING the caller asked for} */
    public final String getAlgorithm()
    {
        return algorithm;
    }

    /**
     * {@return the key {@code keySpec} describes}
     *
     * @throws InvalidKeySpecException if {@code keySpec} is not a usable {@link PBEKeySpec}
     */
    public final SecretKey generateSecret(KeySpec keySpec) throws InvalidKeySpecException
    {
        return spi.engineGenerateSecret(keySpec);
    }

    /**
     * {@return a specification of {@code key}}
     *
     * @throws InvalidKeySpecException always -- see {@link Impl#engineGetKeySpec}, a STATED divergence
     */
    public final KeySpec getKeySpec(SecretKey key, Class<?> keySpec) throws InvalidKeySpecException
    {
        return spi.engineGetKeySpec(key, keySpec);
    }

    /**
     * {@return {@code key}, if it is one of ours}
     *
     * @throws InvalidKeyException if it is not
     */
    public final SecretKey translateKey(SecretKey key) throws InvalidKeyException
    {
        return spi.engineTranslateKey(key);
    }

    /**
     * The algorithm table. Case-insensitive, and the set is stock's MINUS the two truncated SHA-512
     * variants: {@code crypto.Digest} carries SHA-512 but not SHA-512/224 or SHA-512/256, which are the
     * same compression function under DIFFERENT initial chaining values rather than a truncation of the
     * output -- so answering them by truncating SHA-512 would be wrong in every byte while looking exactly
     * like a key. {@code NoSuchAlgorithmException} is the truthful answer, as it is for SHA-3 on
     * {@code MessageDigest}.
     *
     * <p>{@code PBKDF2WithHmacMD5} is absent because STOCK does not ship it either -- checked, not assumed.
     */
    private static int digestIdFor(String name)
    {
        String n = name.toUpperCase();
        if (n.equals("PBKDF2WITHHMACSHA1"))
        {
            return Digest.SHA1;
        }
        if (n.equals("PBKDF2WITHHMACSHA224"))
        {
            return Digest.SHA224;
        }
        if (n.equals("PBKDF2WITHHMACSHA256"))
        {
            return Digest.SHA256;
        }
        if (n.equals("PBKDF2WITHHMACSHA384"))
        {
            return Digest.SHA384;
        }
        if (n.equals("PBKDF2WITHHMACSHA512"))
        {
            return Digest.SHA512;
        }
        return -1;
    }

    /**
     * Encode a {@code char[]} as UTF-8, exactly as stock's
     * {@code UTF_8.encode(CharBuffer.wrap(passwd))} does.
     *
     * <p>A surrogate PAIR becomes one four-byte sequence; an UNPAIRED surrogate becomes {@code '?'},
     * because {@code Charset.encode} uses the REPLACE malformed-input action. Written out rather than
     * routed through {@code String}, so the password never becomes an interned, immutable object this code
     * cannot zero.
     */
    private static byte[] utf8(char[] pw)
    {
        int n = 0;
        int i = 0;
        while (i < pw.length)
        {
            int c = pw[i];
            if (c < 0x80)
            {
                n = n + 1;
                i = i + 1;
            }
            else if (c < 0x800)
            {
                n = n + 2;
                i = i + 1;
            }
            else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < pw.length
                    && pw[i + 1] >= 0xDC00 && pw[i + 1] <= 0xDFFF)
            {
                n = n + 4;
                i = i + 2;
            }
            else if (c >= 0xD800 && c <= 0xDFFF)
            {
                n = n + 1;                       // unpaired surrogate -> '?'
                i = i + 1;
            }
            else
            {
                n = n + 3;
                i = i + 1;
            }
        }

        byte[] out = new byte[n];
        int p = 0;
        i = 0;
        while (i < pw.length)
        {
            int c = pw[i];
            if (c < 0x80)
            {
                out[p] = (byte) c;
                p = p + 1;
                i = i + 1;
            }
            else if (c < 0x800)
            {
                out[p] = (byte) (0xC0 | (c >> 6));
                out[p + 1] = (byte) (0x80 | (c & 0x3F));
                p = p + 2;
                i = i + 1;
            }
            else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < pw.length
                    && pw[i + 1] >= 0xDC00 && pw[i + 1] <= 0xDFFF)
            {
                int cp = 0x10000 + ((c - 0xD800) << 10) + (pw[i + 1] - 0xDC00);
                out[p] = (byte) (0xF0 | (cp >> 18));
                out[p + 1] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                out[p + 2] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                out[p + 3] = (byte) (0x80 | (cp & 0x3F));
                p = p + 4;
                i = i + 2;
            }
            else if (c >= 0xD800 && c <= 0xDFFF)
            {
                out[p] = (byte) '?';
                p = p + 1;
                i = i + 1;
            }
            else
            {
                out[p] = (byte) (0xE0 | (c >> 12));
                out[p + 1] = (byte) (0x80 | ((c >> 6) & 0x3F));
                out[p + 2] = (byte) (0x80 | (c & 0x3F));
                p = p + 3;
                i = i + 1;
            }
        }
        return out;
    }

    /** The PBKDF2 SPI, over {@link Pbkdf2#derive}. */
    private static final class Impl extends SecretKeyFactorySpi
    {
        private final int digestId;
        private final String algorithm;

        Impl(int digestId, String algorithm)
        {
            this.digestId = digestId;
            this.algorithm = algorithm;
        }

        protected SecretKey engineGenerateSecret(KeySpec keySpec) throws InvalidKeySpecException
        {
            if (!(keySpec instanceof PBEKeySpec))
            {
                // Stock's wording, and a null spec lands here too rather than as an NPE -- MEASURED.
                throw new InvalidKeySpecException("Only PBEKeySpec is accepted");
            }
            PBEKeySpec spec = (PBEKeySpec) keySpec;

            // BITS, truncated by integer division. MEASURED against a host JVM -- see the class comment.
            int dkLen = spec.getKeyLength() / 8;
            if (dkLen < 1)
            {
                // STATED DIVERGENCE: stock hands back a ZERO-LENGTH key for 1..7 bits. A key of no bytes
                // is unusable whatever produced it, and a caller who reaches this asked for bytes and wrote
                // bits; refusing names the mistake where an empty key would be discovered somewhere else.
                throw new InvalidKeySpecException("keyLength is in BITS and " + spec.getKeyLength()
                        + " is under one byte -- did you mean " + (spec.getKeyLength() * 8) + "?");
            }

            char[] pw = spec.getPassword();
            byte[] pwBytes = utf8(pw);
            byte[] salt = spec.getSalt();
            byte[] out = new byte[dkLen];
            try
            {
                Pbkdf2.derive(digestId, pwBytes, pwBytes.length, salt, salt.length,
                        spec.getIterationCount(), out, dkLen);
            }
            finally
            {
                // The encoded password reproduces every key this spec can ever derive, so it is zeroed on
                // the way out however the derivation ended -- the reason SecureRandom zeroes its seed.
                // The char[] is the CALLER's copy (getPassword clones); clearing it is their business.
                int z = 0;
                while (z < pwBytes.length)
                {
                    pwBytes[z] = 0;
                    z = z + 1;
                }
            }
            return new SecretKeySpec(out, algorithm);
        }

        /**
         * STATED DIVERGENCE: this always refuses, where stock can round-trip a {@link PBEKeySpec}.
         *
         * <p>Stock can only do that because its key object RETAINS the password, the salt and the iteration
         * count. Keeping a password alive in a key is the one thing a key should not do, so the key here is
         * a {@link SecretKeySpec} holding the derived bytes and nothing else -- and a factory that cannot
         * recover the password must say so rather than answer with something plausible.
         */
        protected KeySpec engineGetKeySpec(SecretKey key, Class<?> keySpec) throws InvalidKeySpecException
        {
            throw new InvalidKeySpecException("joe-ng's PBKDF2 keys do not retain the password, "
                    + "so no PBEKeySpec can be recovered from one");
        }

        /**
         * {@return {@code key} if it is already one of ours}
         *
         * <p>STATED DIVERGENCE: stock demands a {@code PBEKey} and refuses a {@code SecretKeySpec}. Here the
         * factory's own output IS a SecretKeySpec, so refusing it would make {@code translateKey} reject the
         * very keys this class produces.
         *
         * @throws InvalidKeyException if it is not a RAW key of this factory's algorithm
         */
        protected SecretKey engineTranslateKey(SecretKey key) throws InvalidKeyException
        {
            if (key == null || key.getAlgorithm() == null || key.getFormat() == null
                    || !key.getAlgorithm().equalsIgnoreCase(algorithm)
                    || !key.getFormat().equals("RAW") || key.getEncoded() == null)
            {
                throw new InvalidKeyException("Only " + algorithm + " key with RAW format is accepted");
            }
            return key;
        }
    }
}
