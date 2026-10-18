/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */

/**
 * {@code super.m()} must reach the CLOSEST superclass that declares {@code m}, even when that declaration has
 * never been called -- an {@code invokespecial} naming a class whose own body is not compiled yet. The VM once
 * resolved such a site to the nearest ancestor whose body WAS compiled, skipping the declaring class silently:
 * stock ZipFile's {@code super.close()} ran FilterInputStream.close instead of InflaterInputStream.close, and a
 * closed stream went on reading an ended Inflater for ever.
 *
 * <p>Each arm calls the top ancestor's method FIRST, so it is the compiled one, then makes the first call that
 * reaches the middle declaration -- through {@code super}. Arm 1: the direct superclass declares it. Arm 2: the
 * named superclass only inherits it, from a grandparent that declares it ({@code toString}, so the compiled
 * ancestor above is Object's). Arm 3: the same through a two-argument method. Arm 4: a static, named through a
 * subclass that inherits it from a class hiding a static of the top class. Arms 5 and 6 are the SHAPE that
 * failed, on java.base classes: FilterInputStream.close / FilterOutputStream.close are called first, then a
 * subclass of InflaterInputStream / DeflaterOutputStream calls {@code super.close()} -- the first call that
 * reaches the middle class's close. Bound one level too high, the inflater stream is not marked closed (a read
 * after close answers a byte instead of "Stream closed") and the deflater stream is never finished (no final
 * block, so the bytes do not inflate back).
 *
 * <p>A SHAPE GUARD, NOT A REPRODUCTION -- measured: every arm passes on the commit WITHOUT the fix as well, so the
 * mis-binding needs a registry state this program does not create. The regression evidence is stock
 * {@code ReadAfterClose} under MetalJUnit: 3 of 4 with the fix reverted (case [2] "Inflater has been closed",
 * an endless read loop before the Inflater overlay refused an ended stream), 4 of 4 with it.
 */
public class SuperCallProbe
{
    static final class CountingInflaterStream extends java.util.zip.InflaterInputStream
    {
        CountingInflaterStream(java.io.InputStream in)
        {
            super(in, new java.util.zip.Inflater());
        }

        @Override
        public void close() throws java.io.IOException
        {
            super.close();
        }
    }

    static final class CountingDeflaterStream extends java.util.zip.DeflaterOutputStream
    {
        CountingDeflaterStream(java.io.OutputStream out)
        {
            super(out);
        }

        @Override
        public void close() throws java.io.IOException
        {
            super.close();
        }
    }

    static class A
    {
        String who()
        {
            return "A";
        }

        String pair(int x, String y)
        {
            return "A" + x + y;
        }

        static String st()
        {
            return "A.st";
        }
    }

    static class B extends A
    {
        @Override
        String who()
        {
            return "B";
        }

        @Override
        String pair(int x, String y)
        {
            return "B" + x + y;
        }

        static String st()
        {
            return "B.st";
        }
    }

    static class C extends B
    {
        @Override
        String who()
        {
            return "C>" + super.who();
        }

        @Override
        String pair(int x, String y)
        {
            return "C>" + super.pair(x, y);
        }
    }

    static class G
    {
        @Override
        public String toString()
        {
            return "G";
        }
    }

    static class H extends G
    {
    }

    static class K extends H
    {
        @Override
        public String toString()
        {
            return "K>" + super.toString();
        }
    }

    public static void main(String[] args)
    {
        System.out.println("top first: " + new A().who() + " " + new A().pair(1, "x") + " " + A.st());
        System.out.println("super, direct declaration: " + new C().who());
        System.out.println("super, inherited from a grandparent: " + new K());
        System.out.println("super, two arguments: " + new C().pair(2, "y"));
        System.out.println("inherited static through a subclass: " + C.st());
        try
        {
            // FilterInputStream.close and FilterOutputStream.close run first, so they are the compiled ancestors.
            new java.io.FilterInputStream(new java.io.ByteArrayInputStream(new byte[1])) { }.close();
            new java.io.FilterOutputStream(new java.io.ByteArrayOutputStream()) { }.close();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            CountingDeflaterStream d = new CountingDeflaterStream(raw);
            d.write("finish me".getBytes());
            d.close();
            byte[] packed = raw.toByteArray();
            CountingInflaterStream in = new CountingInflaterStream(new java.io.ByteArrayInputStream(packed));
            String back = new String(in.readNBytes(9));
            System.out.println("super.close() finished the deflater stream: " + back);
            in.close();
            try
            {
                System.out.println("read after super.close(): " + in.read());
            }
            catch (java.io.IOException e)
            {
                System.out.println("read after super.close(): " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        catch (Exception e)
        {
            System.out.println("stream arms threw " + e);
        }
        System.out.println("SuperCallProbe done");
    }
}
