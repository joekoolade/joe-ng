/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package demo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * Where SHA-256 time goes on the metal: the stock {@code MessageDigest} (sun.security.provider.SHA2) against the
 * VM's own {@code crypto.Digest} over the same bytes, and the two halves of the stock block function measured
 * apart -- the 16 big-endian word reads per block through a byte-array-view VarHandle (what
 * {@code ByteArrayAccess.b2iBig64} does) and the same reads done with shifts. Times are relative: run it on QEMU
 * for the ratios, on the Pi for the absolute numbers.
 */
public class ShaSpeedProbe
{
    private static final VarHandle BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    public static void main(String[] args) throws Exception
    {
        int n = 1 << 23;
        if (args.length > 0)
        {
            n = Integer.parseInt(args[0]);
        }
        byte[] data = new byte[n];
        new Random(5).nextBytes(data);
        int chunk = 65536;
        System.out.println("sha: " + n + " bytes in " + chunk + "-byte updates");

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data, 0, 64);
        md.reset();
        long t0 = System.nanoTime();
        int off = 0;
        while (off < n)
        {
            int len = Math.min(chunk, n - off);
            md.update(data, off, len);
            off = off + len;
        }
        byte[] stock = md.digest();
        long t1 = System.nanoTime();
        report("stock MessageDigest", t1 - t0, n);

        crypto.Digest d = new crypto.Digest(crypto.Digest.SHA256);
        t0 = System.nanoTime();
        off = 0;
        while (off < n)
        {
            int len = Math.min(chunk, n - off);
            d.update(data, off, len);
            off = off + len;
        }
        byte[] own = d.digest();
        t1 = System.nanoTime();
        report("crypto.Digest", t1 - t0, n);
        System.out.println("sha: digests equal " + Arrays.equals(stock, own));

        int[] w = new int[16];
        int blocks = n / 64;
        t0 = System.nanoTime();
        int b = 0;
        while (b < blocks)
        {
            int p = b * 64;
            int i = 0;
            while (i < 16)
            {
                w[i] = (int) BE.get(data, p + 4 * i);
                i = i + 1;
            }
            b = b + 1;
        }
        t1 = System.nanoTime();
        int vh = w[3];
        report("word reads, VarHandle", t1 - t0, n);

        t0 = System.nanoTime();
        b = 0;
        while (b < blocks)
        {
            int p = b * 64;
            int i = 0;
            while (i < 16)
            {
                int q = p + 4 * i;
                w[i] = (data[q] << 24) | ((data[q + 1] & 0xFF) << 16) | ((data[q + 2] & 0xFF) << 8)
                        | (data[q + 3] & 0xFF);
                i = i + 1;
            }
            b = b + 1;
        }
        t1 = System.nanoTime();
        report("word reads, shifts", t1 - t0, n);
        System.out.println("sha: word reads agree " + (vh == w[3]));
        System.out.println("sha: done");
    }

    private static void report(String what, long ns, int bytes)
    {
        long ms = ns / 1_000_000L;
        long kbs = ms == 0 ? 0 : (long) bytes * 1000L / 1024L / ms;
        System.out.println("sha: " + what + " " + ms + " ms (" + kbs + " KB/s)");
    }
}
