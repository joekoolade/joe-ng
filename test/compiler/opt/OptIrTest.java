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
import harness.T;

import java.nio.file.Path;

/**
 * The optimizing tier's IR (O1-1): the SSA builder over {@link OptFixture}'s shapes, and the verifier against
 * graphs broken on purpose -- a verifier that passes everything proves nothing, so each check it must make is
 * shown failing once.
 *
 * <p>Run: {@code java compiler.opt.OptIrTest <classesDir>}
 */
public final class OptIrTest
{
    private static ClassFile cf;

    public static void main(String[] args) throws Exception
    {
        Path dir = Path.of(args.length > 0 ? args[0] : "out");
        cf = ClassFile.parse(dir.resolve("compiler/opt/OptFixture.class"));

        Graph straight = build("straight");
        T.eq("straight: blocks", 2, straight.nBlocks);
        T.eq("straight: phis", 0, phis(straight, -1));

        Graph loop = build("loop");
        Block header = loopHeader(loop);
        T.check("loop: has a header", header != null);
        T.eq("loop: header phis (i, s; n is trivial)", 2, header == null ? -1 : header.nPhis);
        T.eq("loop: int phis", 2, phis(loop, Kind.I));

        Graph wj = build("wideJoin");
        T.check("wideJoin: a long phi", phis(wj, Kind.J) >= 1);
        T.check("wideJoin: a double phi", phis(wj, Kind.D) >= 1);

        build("slotReuse");

        Graph tc = build("tryCatch");
        T.check("tryCatch: homes", tc.homes);
        Block h = handler(tc);
        T.check("tryCatch: handler starts with catch", h != null && h.code[0].op == Op.CATCH);
        T.eq("tryCatch: handler loads a and b as ints", 2, loads(h, Kind.I));
        T.check("tryCatch: stores homes", count(tc, Op.STORE_LOCAL) >= 3);

        Graph tf = build("tryFinally");
        T.check("tryFinally: handlers", handler(tf) != null);

        Graph wh = build("wideInHandler");
        T.eq("wideInHandler: handler loads t and n as longs", 2, loads(handler(wh), Kind.J));
        T.check("wideInHandler: loop header", loopHeader(wh) != null);

        Graph sw = build("switches");
        T.eq("switches: two switches", 2, count(sw, Op.SWITCH));

        build("dups");

        Graph w = build("words");
        T.eq("words: Address phi merges p and null", 1, phis(w, Kind.W));

        build("headerAtZero");
        Block h0 = loopHeader(build("headerAtZero"));
        T.check("headerAtZero: bci 0 is a loop header", h0 != null && h0.startBci == 0 && h0.nPhis == 1);

        T.check("pragma: @Optimize read", cf.method("pragma", "(I)I").optimize);
        T.check("pragma: absent elsewhere", !cf.method("loop", "(I)I").optimize);

        negatives();
        T.summary("OptIrTest");
    }

    /** Each corruption must be reported. */
    private static void negatives()
    {
        Graph g = build("loop");
        Block hd = loopHeader(g);
        hd.phis[0].in[0] = null;
        mustFail("phi operand missing", g);

        g = build("loop");
        hd = loopHeader(g);
        Instr p = hd.phis[0];
        Instr[] shorter = new Instr[p.in.length - 1];
        for (int i = 0; i < shorter.length; i++)
        {
            shorter[i] = p.in[i];
        }
        p.in = shorter;
        mustFail("phi arity", g);

        g = build("straight");
        Block b = g.blocks[1];
        Instr mul = find(b, Op.MUL);
        Instr add = find(b, Op.ADD);
        int mi = indexOf(b, mul);
        int ai = indexOf(b, add);
        b.code[mi] = add;
        b.code[ai] = mul;
        mustFail("use before def", g);

        g = build("straight");
        find(g.blocks[1], Op.ADD).kind = Kind.J;
        mustFail("kind mismatch", g);

        g = build("straight");
        g.blocks[1].nCode -= 1;
        mustFail("no terminator", g);

        g = build("tryCatch");
        Block hb = handler(g);
        Instr div = find(g, Op.DIV);
        Instr later = hb.code[hb.nCode - 2];
        div.state.locals[0] = later;
        mustFail("framestate value does not dominate", g);

        g = build("loop");
        hd = loopHeader(g);
        Block body = null;
        for (int i = 0; i < hd.nPreds; i++)
        {
            if (hd.preds[i].rpo > hd.rpo)
            {
                body = hd.preds[i];
            }
        }
        Instr fromBody = body.code[0];
        for (int i = 0; i < hd.nPreds; i++)
        {
            if (hd.preds[i].rpo < hd.rpo && fromBody.kind == Kind.I)
            {
                hd.phis[0].in[i] = fromBody;                 // a value from the loop body on the entry edge
            }
        }
        mustFail("phi operand does not dominate its predecessor", g);
    }

    private static void mustFail(String name, Graph g)
    {
        String err = Verifier.verify(g);
        T.check("verifier catches: " + name + (err == null ? "" : " (" + err + ")"), err != null);
        if (System.getenv("VERBOSE") != null)
        {
            System.out.println(name + " -> " + err);
        }
    }

    private static Graph build(String name)
    {
        ClassFile.Method m = null;
        for (ClassFile.Method x : cf.methods())
        {
            if (x.name.equals(name))
            {
                m = x;
            }
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
        int r = oc.compile(cf.bytes(), cf.cpOff(), cf.cpTag(), m.code, m.descOff, m.isStatic, m.isSynchronized,
                           m.maxLocals, es, ee, eh, ec, n);
        String why = r == OptCompiler.REJECTED ? SsaBuilder.reasonName(oc.reason()) + " @" + oc.reasonBci()
                     : oc.error();
        T.check(name + ": accepted and verified" + (why == null ? "" : " (" + why + ")"), r == OptCompiler.ACCEPTED);
        if (r != OptCompiler.ACCEPTED && oc.graph() != null)
        {
            System.out.print(oc.graph().dump());
        }
        return oc.graph();
    }

    private static int phis(Graph g, int kind)
    {
        int n = 0;
        for (int b = 0; b < g.nBlocks; b++)
        {
            for (int i = 0; i < g.blocks[b].nPhis; i++)
            {
                if (kind < 0 || g.blocks[b].phis[i].kind == kind)
                {
                    n += 1;
                }
            }
        }
        return n;
    }

    private static int count(Graph g, int op)
    {
        int n = 0;
        for (int b = 0; b < g.nBlocks; b++)
        {
            for (int i = 0; i < g.blocks[b].nCode; i++)
            {
                if (g.blocks[b].code[i].op == op)
                {
                    n += 1;
                }
            }
        }
        return n;
    }

    private static int loads(Block h, int kind)
    {
        int n = 0;
        for (int i = 0; h != null && i < h.nCode; i++)
        {
            if (h.code[i].op == Op.LOAD_LOCAL && h.code[i].kind == kind)
            {
                n += 1;
            }
        }
        return n;
    }

    private static Block loopHeader(Graph g)
    {
        for (int b = 0; b < g.nBlocks; b++)
        {
            if (g.blocks[b].loopHeader && !g.blocks[b].isHandler)
            {
                return g.blocks[b];
            }
        }
        return null;
    }

    private static Block handler(Graph g)
    {
        for (int b = 0; b < g.nBlocks; b++)
        {
            if (g.blocks[b].isHandler)
            {
                return g.blocks[b];
            }
        }
        return null;
    }

    private static Instr find(Block b, int op)
    {
        for (int i = 0; i < b.nCode; i++)
        {
            if (b.code[i].op == op)
            {
                return b.code[i];
            }
        }
        return null;
    }

    private static Instr find(Graph g, int op)
    {
        for (int b = 0; b < g.nBlocks; b++)
        {
            Instr x = find(g.blocks[b], op);
            if (x != null)
            {
                return x;
            }
        }
        return null;
    }

    private static int indexOf(Block b, Instr x)
    {
        for (int i = 0; i < b.nCode; i++)
        {
            if (b.code[i] == x)
            {
                return i;
            }
        }
        return -1;
    }
}
