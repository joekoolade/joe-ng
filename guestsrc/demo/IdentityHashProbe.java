/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-23
 */
package demo;

import java.util.ArrayList;
import java.util.List;
import jdk.internal.misc.MetalGcTest;

/**
 * Identity hashes that survive a move (frames/GC arc S7-1), checked with the VM's test mover
 * ({@link MetalGcTest#move}): a copy that carries the original's hash, as a copying collector's copy would. Metal-only
 * (the mover is a VM native). Checks:
 * <ul>
 *   <li>a hashed object's copy answers the original hash, through {@code System.identityHashCode} and
 *       {@code Object.hashCode} alike, and so does a copy of that copy;</li>
 *   <li>an object moved BEFORE its hash is taken gets a hash of its own, not the original's;</li>
 *   <li>moved hashes survive collections with churn between them, which purge the table's dead entries -- including
 *       the dead original blocks' addresses, which the allocator reuses;</li>
 *   <li>four threads hashing at once (the table's lock) all agree.</li>
 * </ul>
 * Prints {@code IdentityHashProbe PASS} only when every check holds.
 */
public class IdentityHashProbe
{
    private static int failures;

    private static void check(boolean ok, String what)
    {
        if (!ok)
        {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception
    {
        // 1. A hashed object's copy keeps the hash; so does a copy of the copy.
        Object a = new Object();
        int h = System.identityHashCode(a);
        Object b = MetalGcTest.move(a);
        check(b != a, "move returned a new object");
        check(System.identityHashCode(b) == h, "a moved object keeps its identity hash");
        check(b.hashCode() == h, "Object.hashCode of a moved object keeps it too");
        Object c = MetalGcTest.move(b);
        check(System.identityHashCode(c) == h, "a twice-moved object keeps its identity hash");

        // 2. Moved before hashing: the copy's hash is its own.
        Object d = new Object();
        Object e = MetalGcTest.move(d);
        int he = System.identityHashCode(e);
        int hd = System.identityHashCode(d);
        check(he != hd, "an object moved before hashing gets its own hash");
        check(System.identityHashCode(e) == he, "... and keeps it");

        // 3. Many moved hashes across collections with churn (dead originals' blocks get reused).
        int n = 2000;
        Object[] moved = new Object[n];
        int[] hashes = new int[n];
        int i = 0;
        while (i < n)
        {
            Object o = (i & 1) == 0 ? new Object() : new int[i & 15];
            hashes[i] = System.identityHashCode(o);
            moved[i] = MetalGcTest.move(o);
            i += 1;
        }
        int round = 0;
        while (round < 3)
        {
            churn();
            System.gc();
            round += 1;
        }
        int bad = 0;
        i = 0;
        while (i < n)
        {
            if (System.identityHashCode(moved[i]) != hashes[i])
            {
                bad += 1;
            }
            i += 1;
        }
        check(bad == 0, "moved hashes after 3 collections (" + bad + " of " + n + " wrong)");
        check(System.identityHashCode(c) == h, "the first moved hash after the collections");

        // 4. Four threads hashing a shared set at once.
        Object[] shared = new Object[4000];
        i = 0;
        while (i < shared.length)
        {
            shared[i] = new Object();
            i += 1;
        }
        int[][] seen = new int[4][shared.length];
        int[] strides = {1, 3, 7, 11};                   // each coprime with 4000: every thread visits every slot
        Thread[] ts = new Thread[4];
        int t = 0;
        while (t < 4)
        {
            final int me = t;
            ts[t] = new Thread(() ->
            {
                int j = 0;
                while (j < shared.length)
                {
                    int k = (j * strides[me]) % shared.length;
                    seen[me][k] = System.identityHashCode(shared[k]);
                    j += 1;
                }
            });
            ts[t].start();
            t += 1;
        }
        t = 0;
        while (t < 4)
        {
            ts[t].join();
            t += 1;
        }
        int disagree = 0;
        i = 0;
        while (i < shared.length)
        {
            int base = System.identityHashCode(shared[i]);
            t = 0;
            while (t < 4)
            {
                if (seen[t][i] != base)
                {
                    disagree += 1;
                }
                t += 1;
            }
            i += 1;
        }
        check(disagree == 0, "four threads agree on every hash (" + disagree + " disagreements)");

        if (failures == 0)
        {
            System.out.println("IdentityHashProbe PASS");
        }
        else
        {
            System.out.println("IdentityHashProbe FAILED " + failures);
        }
    }

    private static void churn()
    {
        List<Object> junk = new ArrayList<>();
        int i = 0;
        while (i < 20000)
        {
            Object o = new Object();
            System.identityHashCode(o);
            junk.add(o);
            if (junk.size() > 1000)
            {
                junk.clear();
            }
            i += 1;
        }
    }
}
