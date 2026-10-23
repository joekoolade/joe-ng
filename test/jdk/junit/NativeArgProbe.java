/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-23
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * Bad arguments to the java.base calls that end in a VM native: reflective array creation, access and length,
 * clone, identity hash, intern, sleep and join, class lookup by name, and the array-copying library methods built
 * on {@code System.arraycopy}. Each line prints the exception's class and message, or the result. A native that
 * trusts its arguments answers a plausible value here, or corrupts memory, instead of throwing. A
 * NullPointerException prints its class only.
 */
public class NativeArgProbe
{
    static void run(String what, Callable<Object> c)
    {
        try
        {
            Object r = c.call();
            String s = r instanceof int[] a ? Arrays.toString(a)
                    : r instanceof Object[] a ? Arrays.deepToString(a)
                    : r instanceof Class<?> k ? "class " + k.getName()
                    : String.valueOf(r);
            System.out.println(what + ": " + s);
        }
        catch (Throwable e)
        {
            // An NPE's message is HotSpot's helpful text, a bytecode-position detail the VM leaves null by design.
            String m = e instanceof NullPointerException ? "" : ": " + e.getMessage();
            System.out.println(what + ": " + e.getClass().getName() + m);
        }
    }

    /** Its initializer prints: naming its ARRAY class must not run it. */
    static class Noisy
    {
        static
        {
            System.out.println("Noisy initialized");
        }

        static int touch()
        {
            return 1;
        }
    }

    static int n = -3;

    public static void main(String[] args)
    {
        run("newInstance(String, -1)", () -> Array.newInstance(String.class, -1));
        run("newInstance(int, -1)", () -> Array.newInstance(int.class, -1));
        run("newInstance(null, 1)", () -> Array.newInstance(null, 1));
        run("newInstance(void, 1)", () -> Array.newInstance(void.class, 1));
        run("newInstance(int, 2, -1)", () -> Array.newInstance(int.class, 2, -1));
        run("newInstance(int, new int[0])", () -> Array.newInstance(int.class, new int[0]));
        run("newInstance(int, 0).length", () -> ((int[]) Array.newInstance(int.class, 0)).length);
        run("getLength(String)", () -> Array.getLength("s"));
        run("getLength(null)", () -> Array.getLength(null));
        run("get(int[2], 2)", () -> Array.get(new int[2], 2));
        run("get(int[2], -1)", () -> Array.get(new int[2], -1));
        run("getInt(String[1], 0)", () -> Array.getInt(new String[] { "x" }, 0));
        run("getInt(long[1], 0)", () -> Array.getInt(new long[] { 5L }, 0));
        run("getLong(int[1], 0)", () -> Array.getLong(new int[] { 5 }, 0));
        run("set(String[1], 0, 7)", () ->
        {
            Array.set(new String[1], 0, 7);
            return "set";
        });
        run("set(int[1], 0, 7L)", () ->
        {
            Array.set(new int[1], 0, 7L);
            return "set";
        });
        run("setInt(long[1], 0, 3) then get", () ->
        {
            long[] a = new long[1];
            Array.setInt(a, 0, 3);
            return a[0];
        });
        run("set(int[1], 0, null)", () ->
        {
            Array.set(new int[1], 0, null);
            return "set";
        });
        run("Arrays.copyOf(int[2], -1)", () -> Arrays.copyOf(new int[2], -1));
        run("Arrays.copyOf(Object[], 1, Integer[].class) of a String", () ->
                Arrays.copyOf(new Object[] { "s" }, 1, Integer[].class));
        run("Arrays.copyOfRange(int[2], 3, 4)", () -> Arrays.copyOfRange(new int[2], 3, 4));
        run("Arrays.copyOfRange(int[2], 1, 0)", () -> Arrays.copyOfRange(new int[2], 1, 0));
        run("Arrays.copyOfRange(int[3], 1, 5)", () -> Arrays.copyOfRange(new int[] { 1, 2, 3 }, 1, 5));
        run("int[] clone is a copy", () ->
        {
            int[] a = { 1, 2 };
            int[] b = a.clone();
            b[0] = 9;
            return a[0] + "," + b[0] + " same=" + (a == b) + " class=" + (b.getClass() == int[].class);
        });
        run("String[][] clone is shallow", () ->
        {
            String[][] a = { { "x" } };
            String[][] b = a.clone();
            return (a[0] == b[0]) + " class=" + (b.getClass() == String[][].class);
        });
        run("identityHashCode(null)", () -> System.identityHashCode(null));
        run("Thread.sleep(-1)", () ->
        {
            Thread.sleep(-1);
            return "slept";
        });
        run("Thread.sleep(0, 1000000)", () ->
        {
            Thread.sleep(0, 1000000);
            return "slept";
        });
        run("join(-1)", () ->
        {
            Thread.currentThread().join(-1);
            return "joined";
        });
        run("Class.forName(null)", () -> Class.forName(null));
        run("Class.forName(\"\")", () -> Class.forName(""));
        run("Class.forName(\"[I\")", () -> Class.forName("[I"));
        run("Class.forName(\"int\")", () -> Class.forName("int"));
        run("Class.forName(\"java/lang/String\")", () -> Class.forName("java/lang/String"));
        run("Class.forName(\"[Ljava.lang.String;\")", () -> Class.forName("[Ljava.lang.String;"));
        run("String.valueOf(char[], 1, 5)", () -> String.valueOf(new char[] { 'a', 'b' }, 1, 5));
        run("new String(byte[], -1, 1)", () -> new String(new byte[2], -1, 1));
        run("StringBuilder.append(char[], 1, 5)", () -> new StringBuilder().append(new char[] { 'a' }, 1, 5));
        run("newInstance(void, -1)", () -> Array.newInstance(void.class, -1));
        run("newInstance(void, 2, -1)", () -> Array.newInstance(void.class, 2, -1));
        run("get(Object[1], 1)", () -> Array.get(new Object[1], 1));
        run("set(String[1], 1, 7)", () ->
        {
            Array.set(new String[1], 1, 7);
            return "set";
        });
        run("set(int[1], 5, null)", () ->
        {
            Array.set(new int[1], 5, null);
            return "set";
        });
        run("set(int[1], 0, \"s\")", () ->
        {
            Array.set(new int[1], 0, "s");
            return "set";
        });
        run("setInt(Object[1], 0, 1)", () ->
        {
            Array.setInt(new Object[1], 0, 1);
            return "set";
        });
        run("getInt(String, 0)", () -> Array.getInt("s", 0));
        run("getInt(int[1], 1)", () -> Array.getInt(new int[1], 1));
        run("set(long[1], 0, Integer 3) then get", () ->
        {
            long[] a = new long[1];
            Array.set(a, 0, 3);
            return a[0];
        });
        run("new int[n]", () -> new int[n]);
        run("new String[n]", () -> new String[n]);
        run("new int[2][n]", () -> new int[2][n]);
        run("Class.forName(\"[[I\") == int[][].class", () -> Class.forName("[[I") == int[][].class);
        run("Class.forName(\"[I\") == int[].class", () -> Class.forName("[I") == int[].class);
        run("Class.forName(\"[Ljava.lang.String;\") == String[].class", () ->
                Class.forName("[Ljava.lang.String;") == String[].class);
        run("Class.forName(\"[V\")", () -> Class.forName("[V"));
        run("Class.forName(\"[\")", () -> Class.forName("["));
        run("Class.forName(\"[II\")", () -> Class.forName("[II"));
        run("Class.forName(\"[L;\")", () -> Class.forName("[L;"));
        run("Class.forName(\"[Ljava.lang.String\")", () -> Class.forName("[Ljava.lang.String"));
        run("Class.forName(\"[Ljava/lang/String;\")", () -> Class.forName("[Ljava/lang/String;"));
        run("Class.forName(\"[LNoSuchClass;\")", () -> Class.forName("[LNoSuchClass;"));
        run("Class.forName of Noisy[] (no initializer)", () ->
                Class.forName("[LNativeArgProbe$Noisy;").getComponentType().getName());
        run("then Noisy.touch()", () -> Noisy.touch());
        System.out.println("NativeArgProbe done");
    }
}
