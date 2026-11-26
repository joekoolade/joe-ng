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
package compiler.opt;

import classfile.ClassFile;
import compiler.RefMaps;
import compiler.RefMapsOracle;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build and verify the SSA graph of every method in a corpus far larger than the image: the seed JDK's whole
 * {@code java.base} (from {@code jrt:/}) and every class under the given directories. A malformed graph or a crash
 * is a builder bug and fails the run; a rejection is a shape the tier does not cover and is counted by reason.
 *
 * <p>Run: {@code java compiler.opt.OptCorpus [--jrt | --jrt-all] <dir>...} ({@code --jrt-all}: every module)
 */
public final class OptCorpus
{
    private static int classes;
    private static int accepted;
    private static int malformed;
    private static int crashed;
    private static final int[] byReason = new int[SsaBuilder.REASONS];
    private static final List<String> examples = new ArrayList<>();
    private static boolean oracle;
    private static long statesChecked;
    private static int oracleMismatch;
    private static int oracleUnsolved;
    private static int oracleCoarser;

    public static void main(String[] args) throws Exception
    {
        for (String a : args)
        {
            if (a.equals("--oracle"))
            {
                oracle = true;
            }
            else if (a.equals("--jrt") || a.equals("--jrt-all"))
            {
                FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
                walk(jrt.getPath(a.equals("--jrt") ? "/modules/java.base" : "/modules"));
            }
            else
            {
                walk(Path.of(a));
            }
        }
        StringBuilder sb = new StringBuilder("OptCorpus: classes=").append(classes).append(" verified=")
                .append(accepted).append(" malformed=").append(malformed).append(" crashed=").append(crashed);
        for (int i = 1; i < byReason.length; i++)
        {
            if (byReason[i] > 0)
            {
                sb.append(" [").append(SsaBuilder.reasonName(i)).append('=').append(byReason[i]).append(']');
            }
        }
        if (oracle)
        {
            sb.append(" oracle: states=").append(statesChecked).append(" mismatch=").append(oracleMismatch)
              .append(" unsolved=").append(oracleUnsolved).append(" refmapsCoarser=").append(oracleCoarser);
        }
        for (String e : examples)
        {
            System.out.println("  " + e);
        }
        System.out.println(sb);
        if (malformed + crashed + oracleMismatch > 0)
        {
            System.exit(1);
        }
    }

    private static void walk(Path root) throws IOException
    {
        List<Path> files;
        try (Stream<Path> s = Files.walk(root))
        {
            files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        for (Path p : files)
        {
            ClassFile cf;
            try
            {
                cf = new ClassFile(Files.readAllBytes(p));
            }
            catch (RuntimeException | IOException e)
            {
                continue;                                    // module-info and friends the parser does not model
            }
            classes += 1;
            for (ClassFile.Method m : cf.methods())
            {
                if (m.code != null)
                {
                    one(cf, m, p.toString());
                }
            }
        }
    }

    private static void one(ClassFile cf, ClassFile.Method m, String where)
    {
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
        String key = where + " " + m.name + m.descriptor;
        OptCompiler oc = new OptCompiler();
        int r;
        try
        {
            r = oc.compile(cf.bytes(), cf.cpOff(), cf.cpTag(), m.code, m.descOff, m.isStatic, m.isSynchronized,
                           m.maxLocals, es, ee, eh, ec, n);
        }
        catch (RuntimeException e)
        {
            crashed += 1;
            note("CRASH " + key + ": " + e + " at " + e.getStackTrace()[0]);
            return;
        }
        if (r == OptCompiler.ACCEPTED)
        {
            accepted += 1;
            if (oracle)
            {
                checkAgainstRefMaps(cf, m, oc.graph(), key);
            }
        }
        else if (r == OptCompiler.REJECTED)
        {
            byReason[oc.reason()] += 1;
            if (oc.reason() != SsaBuilder.R_JSR)
            {
                note("rejected " + key + " @" + oc.reasonBci() + ": " + SsaBuilder.reasonName(oc.reason()));
            }
        }
        else
        {
            malformed += 1;
            note("MALFORMED " + key + ": " + oc.error());
        }
    }

    /**
     * Every framestate against RefMaps' entering types at its bci -- an independent dataflow (the Pi-validated GC
     * map one). RefMaps counts a long or double as ONE stack entry; the framestate as two JVM slots (the second
     * null), so the stack is compared value by value.
     */
    private static void checkAgainstRefMaps(ClassFile cf, ClassFile.Method m, Graph g, String key)
    {
        int nStk = m.maxStack + 4;
        RefMapsOracle rm = new RefMapsOracle(cf.bytes(), cf.cpOff(), cf.cpTag(), m.code, m.maxLocals, nStk,
                                             g.exStart, g.exEnd, g.exHandler, g.exCount);
        if (!rm.solve(m.descOff, m.isStatic))
        {
            oracleUnsolved += 1;
            return;
        }
        byte[] loc = new byte[m.maxLocals];
        byte[] stk = new byte[nStk];
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            for (int i = 0; i < blk.nCode; i++)
            {
                Instr x = blk.code[i];
                if (x.state == null)
                {
                    continue;
                }
                int dep = rm.stateAt(x.state.bci, loc, stk);
                statesChecked += 1;
                String why = compare(x.state, dep, loc, stk);
                if (why != null)
                {
                    oracleMismatch += 1;
                    note("ORACLE " + key + " @" + x.state.bci + " " + x.describe() + ": " + why);
                }
            }
        }
    }

    private static String compare(FrameState s, int dep, byte[] loc, byte[] stk)
    {
        if (dep < 0)
        {
            return "RefMaps never reaches this bci";
        }
        int k = 0;
        for (int i = 0; i < s.stack.length; i++)
        {
            Instr v = s.stack[i];
            if (v == null)
            {
                return "null stack slot " + i + " not after a wide value";
            }
            if (k >= dep)
            {
                return "stack deeper than RefMaps' " + dep;
            }
            if (stk[k] == RefMaps.TOP && (v.kind == Kind.W || v.kind == Kind.O || v.kind == Kind.R))
            {
                oracleCoarser += 1;
            }
            else if (expected(v) != stk[k])
            {
                return "stack " + k + ": v" + v.id + ":" + Kind.name(v.kind) + " vs RefMaps " + stk[k];
            }
            k += 1;
            if (Kind.wide(v.kind))
            {
                i += 1;
            }
        }
        if (k != dep)
        {
            return "stack depth " + k + " vs RefMaps " + dep;
        }
        for (int i = 0; i < s.locals.length; i++)
        {
            Instr v = s.locals[i];
            byte t = loc[i];
            if (v == null && (t == RefMaps.RAW || t == RefMaps.WIDE))
            {
                oracleCoarser += 1;              // int/float or long/double merged: RefMaps keeps RAW/WIDE, the
                continue;                        // builder (kinds apart) marks the slot dead -- no bytecode reads it
            }
            if (v != null && t == RefMaps.TOP && (v.kind == Kind.W || v.kind == Kind.O || v.kind == Kind.R))
            {
                oracleCoarser += 1;              // null merged with a word: RefMaps sees REF vs RAW, the builder W
                continue;
            }
            if (v == null ? t != RefMaps.TOP : expected(v) != t)
            {
                return "local " + i + ": " + (v == null ? "dead" : "v" + v.id + ":" + Kind.name(v.kind))
                       + " vs RefMaps " + t;
            }
            if (v != null && Kind.wide(v.kind))
            {
                i += 1;
            }
        }
        return null;
    }

    private static byte expected(Instr v)
    {
        int k = v.kind;
        if (k == Kind.J || k == Kind.D)
        {
            return RefMaps.WIDE;
        }
        if (k == Kind.A)
        {
            return RefMaps.REF;
        }
        if (k == Kind.O)
        {
            return RefMaps.OREF;
        }
        if (k == Kind.R)
        {
            return RefMaps.DREF;
        }
        return RefMaps.RAW;
    }

    private static void note(String s)
    {
        if (examples.size() < 30)
        {
            examples.add(s);
        }
    }
}
