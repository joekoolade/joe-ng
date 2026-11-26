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

import classfile.ClassFile;
import compiler.opt.OptCompiler;
import compiler.opt.SsaBuilder;

import java.util.HashSet;
import java.util.Set;

/**
 * The writer's side of the optimizing tier (PLAN.md "The optimizing compiler"): which methods it is asked to
 * compile, and the counts the build prints. A method is selected by {@code --opt all}, by a {@code --opt} list
 * entry (an exact key {@code owner.name+desc}, {@code owner.*} for a class's methods, {@code pkg/**} for a package
 * tree), or by carrying {@code @magic.Optimize}. O1-1 builds and verifies the IR of each selected method; the code
 * installed is still Baseline's.
 */
final class OptTier
{
    /** The {@code --opt} spec, or null when the flag was not given. */
    static String spec;

    private static final Set<String> seen = new HashSet<>();
    private static int accepted;
    private static int rejected;
    private static int malformed;
    private static final int[] byReason = new int[SsaBuilder.REASONS];
    private static final int REPORT_LIMIT = 20;

    private OptTier()
    {
    }

    static boolean selected(String key, ClassFile.Method m)
    {
        if (m.optimize)
        {
            return true;
        }
        if (spec == null)
        {
            return false;
        }
        for (String part : spec.split(","))
        {
            if (matches(part.trim(), key))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pat, String key)
    {
        if (pat.equals("all"))
        {
            return true;
        }
        int dot = key.indexOf('.');
        String owner = dot < 0 ? key : key.substring(0, dot);
        if (pat.endsWith("/**"))
        {
            return owner.startsWith(pat.substring(0, pat.length() - 2));
        }
        if (pat.endsWith(".*"))
        {
            return owner.equals(pat.substring(0, pat.length() - 2));
        }
        return key.equals(pat);
    }

    /** Build and verify {@code m}'s IR once per key; count and report the outcome. */
    static void check(String key, ClassFile cf, ClassFile.Method m)
    {
        if (m.code == null || !seen.add(key))
        {
            return;
        }
        ClassFile.ExceptionEntry[] ex = m.exceptions;
        int n = ex.length;
        int[] es = new int[n];
        int[] ee = new int[n];
        int[] eh = new int[n];
        int[] ec = new int[n];
        for (int i = 0; i < n; i++)
        {
            es[i] = ex[i].startPc();
            ee[i] = ex[i].endPc();
            eh[i] = ex[i].handlerPc();
            ec[i] = ex[i].catchType();
        }
        OptCompiler oc = new OptCompiler();
        int r;
        try
        {
            r = oc.compile(cf.bytes(), cf.cpOff(), cf.cpTag(), m.code, m.descOff, m.isStatic, m.isSynchronized,
                           m.maxLocals, es, ee, eh, ec, n);
        }
        catch (RuntimeException e)
        {
            malformed += 1;
            if (malformed <= REPORT_LIMIT)
            {
                System.out.println("  opt: CRASH " + key + ": " + e);
                e.printStackTrace(System.out);
            }
            return;
        }
        if (r == OptCompiler.ACCEPTED)
        {
            accepted += 1;
        }
        else if (r == OptCompiler.REJECTED)
        {
            rejected += 1;
            byReason[oc.reason()] += 1;
            if (System.getenv("JOENG_OPT_REJECTS") != null)
            {
                System.out.println("  opt: rejected " + key + " @" + oc.reasonBci() + ": "
                                   + SsaBuilder.reasonName(oc.reason()));
            }
        }
        else
        {
            malformed += 1;
            if (malformed <= REPORT_LIMIT)
            {
                System.out.println("  opt: MALFORMED " + key + ": " + oc.error());
                if (System.getenv("JOENG_OPT_DUMP") != null)
                {
                    System.out.print(oc.graph().dump());
                }
            }
        }
    }

    /** The build's summary line, when the tier was asked for anything. */
    static void report()
    {
        if (seen.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("opt: built=").append(accepted + malformed).append(" verified=").append(accepted)
          .append(" malformed=").append(malformed).append(" rejected=").append(rejected);
        for (int i = 1; i < byReason.length; i++)
        {
            if (byReason[i] > 0)
            {
                sb.append(" [").append(SsaBuilder.reasonName(i)).append('=').append(byReason[i]).append(']');
            }
        }
        System.out.println(sb);
    }
}
