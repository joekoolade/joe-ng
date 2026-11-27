/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package writer;

/**
 * Exception shapes the image does not have, for {@link OptDiff} (O1-5): every installed image method has ONE
 * distinct handler, so a wrong handler choice or a wrong range order cannot show there. Each {@code shape*}
 * method is opt-compiled and run against Baseline with simulated callees -- the {@code f}/{@code g}/{@code obj}
 * calls below answer hashes and sometimes throw, and {@code ex} supplies the object an {@code athrow} throws.
 * Never run on a JVM: the callees' bodies are placeholders.
 */
final class OptShapes
{
    private OptShapes()
    {
    }

    static int f(int x)
    {
        return x;
    }

    static int g(int x)
    {
        return x;
    }

    static long fl(long x)
    {
        return x;
    }

    static Object obj(int x)
    {
        return null;
    }

    static int use(Object o)
    {
        return 0;
    }

    static RuntimeException ex(int x)
    {
        return null;
    }

    /** Nested try ranges with different handlers, and a finally. */
    static int shapeNested(int x)
    {
        int a = x;
        int b = 0;
        try
        {
            try
            {
                a = f(x);
            }
            catch (IllegalStateException e)
            {
                a = g(1);
            }
            b = f(a);
        }
        catch (RuntimeException e)
        {
            b = g(a);
        }
        finally
        {
            a = a + g(b);
        }
        return a + b;
    }

    /** Several catch clauses over one range: tried in table order. */
    static int shapeMultiCatch(int x, int y)
    {
        int r = 0;
        try
        {
            x = f(x);
            y = f(y);
            r = x + y;
        }
        catch (IllegalStateException e)
        {
            r = g(x) + 1;
        }
        catch (IllegalArgumentException e)
        {
            r = g(y) + 2;
        }
        catch (RuntimeException e)
        {
            r = g(r) + 3;
        }
        return r;
    }

    /** An athrow inside typed try ranges: the in-method instanceOf chain, then the unwind. */
    static int shapeTypedThrow(int x)
    {
        int r = 0;
        try
        {
            if (f(x) > 0)
            {
                throw ex(x);
            }
            r = 1;
        }
        catch (IllegalStateException e)
        {
            r = 2 + use(e);
        }
        catch (IllegalArgumentException e)
        {
            r = 3 + use(e);
        }
        return r + g(r);
    }

    /** A try inside a loop: the handler joins the loop again. */
    static int shapeLoop(int n)
    {
        int s = 0;
        for (int i = 0; i < (n & 15); i++)
        {
            try
            {
                s += f(i);
            }
            catch (IllegalStateException e)
            {
                s -= i;
            }
        }
        return s;
    }

    /** References in homes across collections: the handler reads a reference local the collector moved. */
    static Object shapeRefHome(int x)
    {
        Object o = obj(x);
        Object p = obj(x + 1);
        try
        {
            o = obj(f(use(o)));
            p = obj(use(p));
        }
        catch (RuntimeException e)
        {
            return use(o) + use(p) > 0 ? o : p;
        }
        return p;
    }

    /** One local slot reused for a long and then a reference inside one try range. */
    static int shapeSlotReuse(int x)
    {
        int r = 0;
        try
        {
            {
                long l = fl(x);
                r += (int) (l >>> 3);
            }
            {
                Object o = obj(r);
                r += use(o);
            }
            r += f(r);
        }
        catch (RuntimeException e)
        {
            r = -r;
        }
        return r;
    }

    /** Rethrow through an outer handler, and a handler that itself throws into the outer one. */
    static int shapeRethrow(int x)
    {
        int r = x;
        try
        {
            try
            {
                r = f(r);
            }
            catch (RuntimeException e)
            {
                r = use(e);
                throw e;
            }
        }
        catch (IllegalStateException e)
        {
            r += g(r);
        }
        finally
        {
            r += 7;
        }
        return r;
    }

    /** A catch that throws its own exception, caught one level out. */
    static int shapeHandlerThrows(int x)
    {
        int r = 0;
        try
        {
            try
            {
                r = f(x);
            }
            catch (IllegalStateException e)
            {
                r = g(r);
                throw ex(r);
            }
            r += g(r);
        }
        catch (RuntimeException e)
        {
            r = r * 3 + use(e);
        }
        return r;
    }

    static void sink(int x)
    {
    }

    /** A void call that is the last instruction of its try range (the range's end is exclusive). */
    static int shapeVoidEnd(int x)
    {
        int r = x;
        try
        {
            r = f(r);
            sink(r);
        }
        finally
        {
            r = g(r);
        }
        return r;
    }

    /** Wide and reference locals live into a handler together. */
    static long shapeWide(long a, Object o, int x)
    {
        long t = a;
        try
        {
            t = fl(t) + f(x);
            o = obj(x);
            t += use(o);
        }
        catch (RuntimeException e)
        {
            t = t ^ use(o);
        }
        return t;
    }

    static double fd(double x)
    {
        return x;
    }

    static float ff(float x)
    {
        return x;
    }

    /** Double arithmetic, both compare flavours against NaN (forced: random bits are rarely one), and a double live across calls (spilled bits). */
    static int shapeDoubleOps(double a0, double b, int k)
    {
        double a = (k & 3) == 0 ? Double.NaN : ((k & 3) == 1 ? Double.NEGATIVE_INFINITY : a0);   // the unordered cases
        double s = a * b + fd(a - b);
        double q = s / (b == 0.0 ? 1.0 : b);
        int r = 0;
        if (q < a)
        {
            r += 1;
        }
        if (q > a)
        {
            r += 2;
        }
        if (q == q)
        {
            r += 4;
        }
        r += (int) -q;
        r += (int) (long) (s % (b + 3.0));
        return r + k + f((int) fd(q * 0.5));
    }

    /** Float arithmetic and every conversion between int, long, float and double. */
    static long shapeFloatConv(float a, int i, long l, double d)
    {
        float x = a + (float) i - (float) l * 0.25f;
        double y = (double) x + (double) i + (double) l - d;
        float z = (float) y % (a == 0.0f ? 2.0f : a);
        long r = (long) x + (long) y + (int) z + (int) ff(-z) + (long) (float) d;
        return r ^ (x < z ? 1 : 0) ^ (y > d ? 2 : 0);
    }

    /** Float and double arrays, and a double phi round a loop. */
    static double shapeFloatArrays(int n)
    {
        float[] fa = new float[(n & 7) + 1];
        double[] da = new double[fa.length];
        double acc = 0.0;
        for (int i = 0; i < fa.length; i++)
        {
            fa[i] = i * 1.5f;
            da[i] = fd(fa[i]) + acc;
            acc = acc * 0.5 + da[i];
        }
        return acc + fa[fa.length - 1];
    }
}
