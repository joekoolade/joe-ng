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

/**
 * Linear-scan register allocation (after Poletto and Sarkar, 1999) over a {@link RegFile}: intervals in order of
 * start, each given a register free over its whole hull -- free of the active intervals, of the pinned ones and of
 * every instruction that destroys it -- and when none is, the interval ending furthest away is spilled. A spilled
 * register gets a frame slot; each instruction that reads it reloads into a new short-lived register and each
 * that writes it stores from one, and the scan runs again. Those temporaries are never spilled, so the loop ends:
 * an instruction needs at most as many registers as it has operands.
 *
 * <p>No register is callee-saved in O1 (PLAN.md: spill across calls), so a value live across a call meets the
 * call's clobber on every allocatable register and is spilled by the same rule as any other.
 */
public final class LinearScan
{
    private static final int MAX_ROUNDS = 16;

    private final MFunc f;
    private final RegFile rf;
    private Liveness lv;
    /** blocked[cls][p]: pairs [from, to) where register p is taken by a pinned interval or a clobber. */
    private int[][][] blocked;
    private int[][] nBlocked;
    public int rounds;

    private LinearScan(MFunc f, RegFile rf)
    {
        this.f = f;
        this.rf = rf;
    }

    /** Allocate every virtual register of {@code f}; sets {@link MFunc#loc} and {@link MFunc#nSlots}. */
    public static LinearScan run(MFunc f, RegFile rf)
    {
        LinearScan ls = new LinearScan(f, rf);
        for (int round = 0; round < MAX_ROUNDS; round++)
        {
            ls.rounds = round + 1;
            boolean[] spilled = ls.scan();
            if (spilled == null)
            {
                return ls;
            }
            ls.rewrite(spilled);
        }
        throw new IllegalStateException("register allocation did not converge");
    }

    private boolean[] scan()
    {
        lv = new Liveness(f);
        int nv = f.nV;
        int[] loc = new int[nv];
        for (int v = 0; v < nv; v++)
        {
            loc[v] = -1;
        }
        collectBlocked();
        int[] order = sortByStart();
        int[] active = new int[nv];
        int nActive = 0;
        boolean[] spilled = null;
        for (int oi = 0; oi < order.length; oi++)
        {
            int cur = order[oi];
            if (f.fixed[cur] >= 0)
            {
                loc[cur] = f.fixed[cur];
                continue;
            }
            int k = 0;
            for (int a = 0; a < nActive; a++)
            {
                if (lv.to[active[a]] > lv.from[cur])
                {
                    active[k] = active[a];
                    k += 1;
                }
            }
            nActive = k;
            int cls = f.vclass[cur];
            int p = freeReg(cur, cls, loc, active, nActive);
            if (p < 0)
            {
                int victim = pickVictim(cur, cls, loc, active, nActive);
                if (victim < 0 || (!f.noSpill[cur] && lv.to[victim] <= lv.to[cur]))
                {
                    if (f.noSpill[cur])
                    {
                        throw new IllegalStateException("no register for an unspillable value");
                    }
                    spilled = mark(spilled, cur);
                    continue;
                }
                p = loc[victim];
                loc[victim] = -1;
                spilled = mark(spilled, victim);
                int j = 0;
                for (int a = 0; a < nActive; a++)
                {
                    if (active[a] != victim)
                    {
                        active[j] = active[a];
                        j += 1;
                    }
                }
                nActive = j;
            }
            loc[cur] = p;
            active[nActive] = cur;
            nActive += 1;
        }
        if (spilled == null)
        {
            f.loc = loc;
        }
        return spilled;
    }

    private boolean[] mark(boolean[] s, int v)
    {
        boolean[] r = s == null ? new boolean[f.nV] : s;
        r[v] = true;
        return r;
    }

    /** The lowest allocatable register of {@code cls} free over cur's whole interval, or -1. */
    private int freeReg(int cur, int cls, int[] loc, int[] active, int nActive)
    {
        long ok = rf.allocatable(cls);
        for (int a = 0; a < nActive; a++)
        {
            int v = active[a];
            if (f.vclass[v] == cls && loc[v] >= 0)
            {
                ok &= ~(1L << loc[v]);
            }
        }
        for (int p = 0; p < 64; p++)
        {
            if ((ok & (1L << p)) != 0 && !blockedOver(cls, p, lv.from[cur], lv.to[cur]))
            {
                return p;
            }
        }
        return -1;
    }

    /** An active, spillable interval whose register cur could take, ending furthest away; -1 if none. */
    private int pickVictim(int cur, int cls, int[] loc, int[] active, int nActive)
    {
        int best = -1;
        for (int a = 0; a < nActive; a++)
        {
            int v = active[a];
            if (f.vclass[v] != cls || f.noSpill[v] || loc[v] < 0)
            {
                continue;
            }
            if (blockedOver(cls, loc[v], lv.from[cur], lv.to[cur]))
            {
                continue;
            }
            if (best < 0 || lv.to[v] > lv.to[best])
            {
                best = v;
            }
        }
        return best;
    }

    private boolean blockedOver(int cls, int p, int a, int b)
    {
        int[] r = blocked[cls][p];
        int n = nBlocked[cls][p];
        for (int i = 0; i < n; i += 2)
        {
            if (r[i] < b && a < r[i + 1])
            {
                return true;
            }
        }
        return false;
    }

    private void collectBlocked()
    {
        blocked = new int[RegFile.CLASSES][64][];
        nBlocked = new int[RegFile.CLASSES][64];
        for (int v = 0; v < f.nV; v++)
        {
            if (f.fixed[v] >= 0 && lv.present(v))
            {
                block(f.vclass[v], f.fixed[v], lv.from[v], lv.to[v]);
            }
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                for (int cls = 0; cls < RegFile.CLASSES; cls++)
                {
                    long c = x.clobbers(cls);
                    for (int p = 0; c != 0 && p < 64; p++)
                    {
                        if ((c & (1L << p)) != 0)
                        {
                            block(cls, p, x.pos + 1, x.pos + 3);
                        }
                    }
                }
            }
        }
    }

    private void block(int cls, int p, int a, int b)
    {
        int n = nBlocked[cls][p];
        int[] r = blocked[cls][p];
        if (r == null)
        {
            r = new int[8];
        }
        else if (n + 2 > r.length)
        {
            int[] bigger = new int[r.length * 2];
            for (int i = 0; i < n; i++)
            {
                bigger[i] = r[i];
            }
            r = bigger;
        }
        r[n] = a;
        r[n + 1] = b;
        blocked[cls][p] = r;
        nBlocked[cls][p] = n + 2;
    }

    /** Present registers by interval start (pinned first at a tie), insertion sort -- O1 sizes are small. */
    private int[] sortByStart()
    {
        int n = 0;
        for (int v = 0; v < f.nV; v++)
        {
            if (lv.present(v))
            {
                n += 1;
            }
        }
        int[] order = new int[n];
        int k = 0;
        for (int v = 0; v < f.nV; v++)
        {
            if (lv.present(v))
            {
                order[k] = v;
                k += 1;
            }
        }
        for (int i = 1; i < n; i++)
        {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && before(v, order[j]))
            {
                order[j + 1] = order[j];
                j -= 1;
            }
            order[j + 1] = v;
        }
        return order;
    }

    private boolean before(int a, int b)
    {
        if (lv.from[a] != lv.from[b])
        {
            return lv.from[a] < lv.from[b];
        }
        return f.fixed[a] >= 0 && f.fixed[b] < 0;
    }

    /** Give each spilled register a slot; route every read through a reload and every write through a spill. */
    private void rewrite(boolean[] spilled)
    {
        int nv = f.nV;
        int[] slot = new int[nv];
        MInstr[] paired = homeSlots(spilled, slot);
        for (int v = 0; v < nv; v++)
        {
            if (spilled[v] && paired[v] == null)
            {
                slot[v] = f.newSlot(v);
            }
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            int i = 0;
            while (i < blk.n)
            {
                MInstr x = blk.code[i];
                if (x.uses.length == 1 && x.uses[0] < nv && paired[x.uses[0]] == x)
                {
                    blk.remove(i);                           // the home is this value's slot: its SPILL wrote it
                    homesShared += 1;
                    continue;
                }
                int before = 0;
                int after = 0;
                for (int k = 0; k < x.uses.length; k++)
                {
                    int v = x.uses[k];
                    if (v < nv && spilled[v])
                    {
                        int t = temp(v);
                        MInstr r = new MInstr(MInstr.RELOAD, new int[] { t }, null);
                        r.aux = slot[v];
                        blk.insert(i + before, r);
                        before += 1;
                        replaceAll(x, v, t);
                    }
                }
                for (int k = 0; k < x.defs.length; k++)
                {
                    int v = x.defs[k];
                    if (v < nv && spilled[v])
                    {
                        int t = temp(v);
                        x.defs[k] = t;
                        MInstr s = new MInstr(MInstr.SPILL, null, new int[] { t });
                        s.aux = slot[v];
                        blk.insert(i + before + 1 + after, s);
                        after += 1;
                    }
                    else if (v >= nv && x.uses(v))
                    {
                        // a tied read-modify-write of a spilled register: the reload's temporary is also written,
                        // so it is stored back
                        MInstr s = new MInstr(MInstr.SPILL, null, new int[] { v });
                        s.aux = slot[f.origin[v]];   // origin is the spilled register itself
                        blk.insert(i + before + 1 + after, s);
                        after += 1;
                    }
                }
                i += before + 1 + after;
            }
        }
    }

    /** O2-2: spilled values that took their home as their slot. */
    public int homesShared;
    /** Homes already standing as a spilled value's slot, by home: the claimed live intervals, as from/to pairs. */
    private int[][] homeClaims;
    private int[] nHomeClaims;

    /**
     * O2-2: a spilled value whose home is its slot. In a method with handlers a value is stored to its local's HOME
     * (O1-5) and, when spilled, to a spill slot as well, and the home store then reloads it only to copy it across.
     * A spilled {@code v} takes home {@code h} as its slot when its one def reaches {@code HSTORE v -> h} across
     * nothing that can throw or touch h ({@link #defReaches}; so a handler sees h change where it did) and nothing else writes h while v is live -- no other
     * value's HSTORE to h, and no other value already holding h as its slot. The home is then written by v's SPILL
     * and the HSTORE is dropped; every reload of v reads h. The GC map types a home by its kind, which is v's.
     * Answers, by vreg, the HSTORE each such value replaces (null where none) and sets its slot.
     */
    private MInstr[] homeSlots(boolean[] spilled, int[] slot)
    {
        int nv = f.nV;
        MInstr[] paired = new MInstr[nv];
        if (f.nHomes == 0 || rounds > 1)
        {
            // the first round only: its intervals are what the claims are checked against, and a later round's
            // positions (and vregs -- a claimed value is all reload and spill temporaries by then) are new
            return paired;
        }
        homeClaims = new int[f.nHomes][];
        nHomeClaims = new int[f.nHomes];
        int[] defs = new int[nv];
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                for (int d : blk.code[i].defs)
                {
                    if (d < nv)
                    {
                        defs[d] += 1;
                    }
                }
            }
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int j = 1; j < blk.n; j++)
            {
                MInstr h = blk.code[j];
                if (h.op != A64Select.HSTORE)
                {
                    continue;
                }
                int v = h.uses[0];
                if (v >= nv || !spilled[v] || defs[v] != 1 || paired[v] != null)
                {
                    continue;
                }
                int home = h.aux;
                if (!defReaches(blk, j, v, home))
                {
                    continue;
                }
                if (!homeFree(home, v, h))
                {
                    continue;
                }
                paired[v] = h;
                slot[v] = home;
                claim(home, lv.from[v], lv.to[v]);
            }
        }
        return paired;
    }

    /**
     * Whether {@code v}'s def is earlier in the same block than the HSTORE at {@code j}, with only instructions
     * between that cannot throw or touch {@code home}: yieldpoints (the prologue's poll lies before every try range,
     * and a poll never throws), stores to OTHER homes and moves -- the parameters' home stores follow the
     * prologue's poll and each other. So writing the home at the def is invisible to a handler.
     */
    private static boolean defReaches(MBlock blk, int j, int v, int home)
    {
        for (int i = j - 1; i >= 0; i--)
        {
            MInstr y = blk.code[i];
            for (int d : y.defs)
            {
                if (d == v)
                {
                    return y.tied < 0;
                }
            }
            boolean quiet = y.op == A64Select.YIELD || y.op == MInstr.MOVE
                            || (y.op == A64Select.HSTORE && y.aux != home);
            if (!quiet)
            {
                return false;
            }
        }
        return false;
    }

    /** Whether nothing but {@code own} writes {@code home} inside v's live interval. */
    private boolean homeFree(int home, int v, MInstr own)
    {
        int a = lv.from[v];
        int z = lv.to[v];
        for (int k = 0; k < nHomeClaims[home]; k++)
        {
            if (homeClaims[home][2 * k] <= z && a <= homeClaims[home][2 * k + 1])
            {
                return false;
            }
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                MInstr y = blk.code[i];
                boolean writes = (y.op == A64Select.HSTORE || y.op == MInstr.SPILL) && y.aux == home;
                if (writes && y != own && y.pos >= a && y.pos <= z)
                {
                    return false;
                }
            }
        }
        return true;
    }

    private void claim(int home, int a, int z)
    {
        int n = nHomeClaims[home];
        if (homeClaims[home] == null || homeClaims[home].length < 2 * n + 2)
        {
            int[] g = new int[2 * n + 8];
            for (int k = 0; k < 2 * n; k++)
            {
                g[k] = homeClaims[home] == null ? 0 : homeClaims[home][k];
            }
            homeClaims[home] = g;
        }
        homeClaims[home][2 * n] = a;
        homeClaims[home][2 * n + 1] = z;
        nHomeClaims[home] = n + 1;
    }

    private int temp(int v)
    {
        int t = f.newVreg(f.vclass[v]);
        f.noSpill[t] = true;
        f.origin[t] = f.origin[v];
        f.copyGc(t, v);
        return t;
    }

    private static void replaceAll(MInstr x, int v, int t)
    {
        for (int k = 0; k < x.uses.length; k++)
        {
            if (x.uses[k] == v)
            {
                x.uses[k] = t;
            }
        }
        for (int k = 0; k < x.defs.length; k++)
        {
            if (x.defs[k] == v)
            {
                x.defs[k] = t;
            }
        }
    }
}
