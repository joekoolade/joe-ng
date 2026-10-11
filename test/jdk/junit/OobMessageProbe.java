/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * OUT-OF-BOUNDS EXCEPTIONS WHOSE MESSAGE IS BUILT BY {@code jdk.internal.util.Preconditions} -- the path a
 * {@code byteArrayViewVarHandle} bounds check takes, and that #326 (stock {@code Locale}) broke: the view's
 * out-of-bounds read threw {@code NullPointerException} instead of {@code ArrayIndexOutOfBoundsException}.
 * {@code Preconditions.outOfBoundsMessage} formats with {@code String.format}, so the arms separate "the formatter"
 * from "the exception path" from "the view handle". Prints class and message; on a wrong class it also dumps the
 * stack, which is the point of the probe. The host diff is the gate (no stack is printed when correct).
 *
 * <p>ROOT CAUSE, found by bisecting to #326: {@code Locale} is on the writer's {@code noSnapshot} list, so its statics
 * were baked zero and its {@code <clinit>} deferred to "the first active use" -- but the first use here is BAKED code
 * ({@code String.format -> Formatter -> Locale.getDefault}), which carries no init guard, so
 * {@code Locale.defaultLocale} read null. Fixed by running every unsnapshotted class's initializer at launch
 * ({@code Loader.initUnsnapshotted}).
 */
public class OobMessageProbe
{
    public static void main(String[] args)
    {
        System.out.println("format -> " + String.format("Index %s out of bounds for length %s", 5, 3));
        arm("Objects.checkIndex(5, 3)", () -> Objects.checkIndex(5, 3));
        arm("Objects.checkFromToIndex(2, 9, 4)", () -> Objects.checkFromToIndex(2, 9, 4));
        try
        {
            int[] a = new int[2];
            a[5] = 1;
            System.out.println("int[2][5] -> no exception");
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            // CLASS ONLY: the JIT's implicit bounds-check throw builds the exception WITHOUT stock's detail
            // message ("Index 5 out of bounds for length 2") -- a recorded gap, not part of this probe's gate.
            System.out.println("int[2][5] -> " + e.getClass().getName());
        }
        VarHandle ii = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
        arm("view get(byte[4], 1)", () -> sink = (int) ii.get(new byte[4], 1));
        arm("view set(byte[4], 1)", () -> ii.set(new byte[4], 1, 0));
        System.out.println("OobMessageProbe done");
    }

    static int sink;

    interface Body
    {
        void run();
    }

    private static void arm(String name, Body b)
    {
        try
        {
            b.run();
            System.out.println(name + " -> no exception");
        }
        catch (IndexOutOfBoundsException e)
        {
            System.out.println(name + " -> " + e.getClass().getName() + ": " + e.getMessage());
        }
        catch (Throwable t)
        {
            System.out.println(name + " -> WRONG " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }
}
