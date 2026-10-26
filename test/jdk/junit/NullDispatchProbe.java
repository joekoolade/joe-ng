/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */

import java.text.Collator;
import java.time.Duration;

/**
 * invokevirtual on a NULL receiver must throw NullPointerException (JVMS 6.5) on every dispatch path the JIT has.
 * The path this pins is the one for a class with NO vtable slot when the caller compiles -- a class the batch
 * closure DEFERS, such as anything in java.text or java.time, which loads only when first used. That path called
 * the late-resolve trampoline without a null check, and the trampoline's resolver answered a null receiver with
 * the denylist trap: a "DENYLIST TRAP" with an empty callee instead of an NPE (found as a null VarHandle.vform).
 * The String arm is the control: a slotted class, which always had its check.
 */
public final class NullDispatchProbe
{
    static Collator collator;
    static Duration duration;
    static String string;

    public static void main(String[] args)
    {
        arm("deferred abstract class", () -> String.valueOf(collator.compare("a", "b")));
        arm("deferred final class", () -> String.valueOf(duration.toMillis()));
        arm("slotted class (control)", () -> String.valueOf(string.length()));
        System.out.println("NullDispatchProbe done");
    }

    private interface Arm
    {
        String run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = "no throw: " + a.run();
        }
        catch (NullPointerException e)
        {
            r = "NullPointerException";
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName();
        }
        System.out.println(what + " = " + r);
    }
}
