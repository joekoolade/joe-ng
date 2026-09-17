/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-13
 */
package demo;

import magic.Magic;

/**
 * Isolate the exception-unwind corruption by UNWIND DEPTH (no incremental load): a cross-method throw caught
 * one frame up (1-deep) vs two frames up (2-deep), each done twice. If 2-deep corrupts where 1-deep does not,
 * the bug is unwind-depth-related, not incremental-load-specific.
 */
public class UnwindDepth
{
    public static void main(String[] args) throws Exception
    {
        Magic.printStr("1-deep: " + oneDeep() + " " + oneDeep() + "\n");
        Magic.printStr("2-deep: " + twoDeep() + " " + twoDeep() + "\n");
        Magic.printStr("done\n");
    }

    // ---- 1-deep: t1() throws, oneDeep() (its direct caller) catches ----
    static String oneDeep()
    {
        try
        {
            t1();
            return "no";
        }
        catch (RuntimeException e)
        {
            return "1";
        }
    }

    static void t1()
    {
        throw new RuntimeException("x");
    }

    // ---- 2-deep: t2b() throws through t2a(), twoDeep() catches ----
    static String twoDeep()
    {
        try
        {
            t2a();
            return "no";
        }
        catch (RuntimeException e)
        {
            return "2";
        }
    }

    static void t2a()
    {
        t2b();
    }

    static void t2b()
    {
        throw new RuntimeException("x");
    }
}
