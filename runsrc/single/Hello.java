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

/** A lone class file in the default package, run by the web server (demo/RunProgram). */
public class Hello
{
    public static void main(String[] args)
    {
        StringBuilder sb = new StringBuilder();
        for (String a : args)
        {
            sb.append(' ').append(a);
        }
        System.out.println("hello from a class file, args:" + sb);
    }
}
