/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-09
 */
package demo;

/**
 * Minimal launched program: prints a banner and echoes its command-line args over System.out. A tiny,
 * fast-loading witness that the OS-style launcher runs an ordinary {@code main(String[])} with the args
 * from the {@code /etc/init} manifest (no VM hooks; stock String/System/PrintStream only).
 */
public class HelloDemo
{
    public static void main(String[] args)
    {
        System.out.println("hello from a launched main()");
        int i = 0;
        while (i < args.length)
        {
            System.out.println(args[i]);
            i = i + 1;
        }
        System.out.println("bye");
    }
}
