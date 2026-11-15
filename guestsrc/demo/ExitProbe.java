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

/**
 * The control for {@link RunProbe}'s exits: a program the VM launched itself is NOT a run, so its
 * {@code System.exit} goes all the way -- the shutdown hook runs, then the board halts with the status
 * ({@code [exit status=0x0000000000000007]}). java.lang.Shutdown is an overlay now, with a check for runs at the
 * top of exit and halt; this is what proves the check lets everything else through.
 */
public class ExitProbe
{
    public static void main(String[] args)
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("ExitProbe hook ran")));
        System.out.println("ExitProbe exiting with 7");
        System.exit(7);
        System.out.println("ExitProbe still running after exit");
    }
}
