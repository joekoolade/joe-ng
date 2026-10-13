/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.util.NoSuchElementException;

/**
 * {@code java.lang.ScopedValue} over the {@code Thread} overlay's bindings field and per-thread cache. Stock makes
 * the cache and the stack search HotSpot natives; this VM keeps both in the thread. The shapes: a binding read
 * inside {@code run} and {@code call}, unbound before and after, a nested rebinding that the outer scope gets
 * back, two values bound at once, the binding restored when the body throws, {@code orElse} and
 * {@code orElseThrow}, a cache hit (the same value read twice), and a new thread that does NOT see the caller's
 * binding. Prints values only; the host diff is the gate.
 */
public class ScopedValueProbe
{
    static final ScopedValue<String> NAME = ScopedValue.newInstance();
    static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception
    {
        System.out.println("bound before=" + NAME.isBound());
        ScopedValue.where(NAME, "outer").run(() ->
        {
            System.out.println("run get=" + NAME.get() + " again=" + NAME.get());
            ScopedValue.where(NAME, "inner").run(() -> System.out.println("nested get=" + NAME.get()));
            System.out.println("after nested=" + NAME.get());
        });
        System.out.println("bound after=" + NAME.isBound());

        String r = ScopedValue.where(NAME, "c").where(DEPTH, 3).call(() -> NAME.get() + DEPTH.get());
        System.out.println("call result=" + r);

        try
        {
            ScopedValue.where(NAME, "boom").run(() ->
            {
                throw new IllegalStateException("x");
            });
        }
        catch (IllegalStateException e)
        {
            System.out.println("threw, bound after=" + NAME.isBound());
        }

        System.out.println("orElse=" + NAME.orElse("dflt"));
        try
        {
            NAME.get();
            System.out.println("unbound get: returned");
        }
        catch (NoSuchElementException e)
        {
            System.out.println("unbound get: NoSuchElementException");
        }
        try
        {
            NAME.orElseThrow(() -> new IllegalArgumentException("none"));
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("orElseThrow: " + e.getMessage());
        }

        ScopedValue.where(NAME, "parent").run(() ->
        {
            boolean[] seen = new boolean[1];
            Thread t = new Thread(() -> seen[0] = NAME.isBound());
            t.start();
            try
            {
                t.join();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            System.out.println("child thread bound=" + seen[0]);
        });
        System.out.println("ScopedValueProbe done");
    }
}
