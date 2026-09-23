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
package javax.crypto.spec;

import java.security.MessageDigest;
import java.security.spec.KeySpec;
import javax.crypto.SecretKey;

/**
 * A secret key held as raw bytes -- what {@link javax.crypto.Mac#init} is given.
 *
 * <p>OVERLAID for a reason that is about this VM rather than about the class: stock's
 * {@code SecretKeySpec} imports {@code jdk.internal.access.SharedSecrets} and carries a
 * {@code readObject} for SERIALIZATION, a subsystem this VM deliberately does not carry. Everything else
 * here IS stock's behaviour, including the two argument checks callers actually depend on -- a null or
 * zero-length key is rejected at CONSTRUCTION, where the mistake is, rather than at first use.
 *
 * <p>The key is COPIED in and COPIED out. That is not defensiveness for its own sake: a caller that kept a
 * reference to the array it passed could mutate a live key underneath the MAC, and one that mutated the
 * array returned by {@link #getEncoded()} could corrupt the key for every other holder.
 *
 * <p>{@link #equals} compares in CONSTANT TIME via {@link MessageDigest#isEqual}, as stock does. An
 * early-exit comparison of secret material leaks how many leading bytes a guess got right, and this class
 * exists to hold secret material.
 */
public class SecretKeySpec implements KeySpec, SecretKey
{
    private static final long serialVersionUID = 6577238317307289933L;

    private final byte[] key;
    private final String algorithm;

    /**
     * A key over all of {@code key}.
     *
     * @param key       the raw key bytes, copied
     * @param algorithm the algorithm it is for
     * @throws IllegalArgumentException if {@code key} is empty or {@code algorithm} is null
     * @throws NullPointerException     if {@code key} is null
     */
    public SecretKeySpec(byte[] key, String algorithm)
    {
        this(key, 0, key == null ? 0 : key.length, algorithm);
    }

    /**
     * A key over {@code key[offset..offset+len)}.
     *
     * @throws IllegalArgumentException  if the key is empty or the algorithm is null
     * @throws NullPointerException      if {@code key} is null
     * @throws ArrayIndexOutOfBoundsException if the range lies outside {@code key}
     */
    public SecretKeySpec(byte[] key, int offset, int len, String algorithm)
    {
        if (key == null)
        {
            throw new NullPointerException("Missing key argument");
        }
        if (algorithm == null)
        {
            throw new IllegalArgumentException("Missing algorithm");
        }
        if (key.length - offset < len)
        {
            throw new IllegalArgumentException("Invalid offset/length combination");
        }
        if (len < 0 || offset < 0)
        {
            throw new ArrayIndexOutOfBoundsException("len or offset is negative");
        }
        if (len == 0)
        {
            throw new IllegalArgumentException("Empty key");
        }
        this.key = new byte[len];
        System.arraycopy(key, offset, this.key, 0, len);
        this.algorithm = algorithm;
    }

    /** {@return the name of the algorithm this key is for} */
    @Override
    public String getAlgorithm()
    {
        return algorithm;
    }

    /** {@return {@code "RAW"}} These key bytes are the key, not an encoding of one. */
    @Override
    public String getFormat()
    {
        return "RAW";
    }

    /** {@return a COPY of the key bytes} */
    @Override
    public byte[] getEncoded()
    {
        byte[] out = new byte[key.length];
        System.arraycopy(key, 0, out, 0, key.length);
        return out;
    }

    /** {@return a hash over the key bytes and the algorithm name, case-insensitively as stock is} */
    @Override
    public int hashCode()
    {
        int h = 0;
        int i = 1;
        while (i < key.length)
        {
            h = h + (key[i] * i);
            i = i + 1;
        }
        return h ^ algorithm.toLowerCase().hashCode();
    }

    /**
     * {@return whether {@code obj} is a secret key with the same algorithm and the same bytes}
     *
     * <p>The byte comparison is CONSTANT TIME. The algorithm name is compared case-insensitively, as stock
     * does, because a provider is free to hand back a differently-cased name for the same algorithm.
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof SecretKey))
        {
            return false;
        }
        SecretKey other = (SecretKey) obj;
        if (!algorithm.equalsIgnoreCase(other.getAlgorithm()))
        {
            return false;
        }
        byte[] theirs = other.getEncoded();
        boolean same = MessageDigest.isEqual(key, theirs);
        if (theirs != null)
        {
            int i = 0;
            while (i < theirs.length)
            {
                theirs[i] = 0;          // a copy of somebody's key: do not leave it in the heap
                i = i + 1;
            }
        }
        return same;
    }
}
