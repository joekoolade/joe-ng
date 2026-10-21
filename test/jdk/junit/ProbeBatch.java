/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-21
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.lang.reflect.InvocationTargetException;

/**
 * Runs several probes in ONE boot: each argument names a probe class whose {@code main} is invoked with no
 * arguments, in order, after a {@code @@@ ProbeBatch <name>} marker line. {@code scripts/Regress.java} splits the
 * output at the markers and compares each section with that probe's expected output.
 *
 * <p>A boot costs ~40 s on QEMU before main runs (most of it the first load batch's reachability mark), while a
 * probe itself runs in seconds -- so a batch pays the boot once instead of once per probe. The price is that the
 * probes share one VM: a class one probe initializes is already initialized for the next. Probes whose subject IS
 * first initialization, ordering, or global state are marked {@code solo} in {@code test/regress/probes.txt} and
 * never come here. A probe that throws is reported and the batch moves on; a probe that halts the VM ends the
 * batch, and the harness reruns every probe whose section is missing on its own.
 */
public class ProbeBatch
{
    public static void main(String[] args)
    {
        for (String name : args)
        {
            System.out.println("@@@ ProbeBatch " + name);
            System.out.flush();
            try
            {
                Class.forName(name).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
            catch (InvocationTargetException e)
            {
                System.out.flush();
                System.out.println("@@@ ProbeBatch " + name + " threw " + e.getCause());
            }
            catch (Throwable t)
            {
                System.out.flush();
                System.out.println("@@@ ProbeBatch " + name + " could not run: " + t);
            }
            System.out.flush();
            System.err.flush();
        }
        System.out.println("@@@ ProbeBatch end");
    }
}
