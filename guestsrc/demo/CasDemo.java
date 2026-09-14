package demo;

import magic.Magic;

/**
 * {@code Magic.cas64} on the metal.
 *
 * <p>The individual encodings are pinned bit-for-bit in {@code A64Test}; what THIS catches is their
 * COMPOSITION -- the three branch offsets in the lowering, which assemble perfectly whatever they point at.
 * Each arm is chosen so a wrong offset cannot pass it:
 *
 * <ul>
 *   <li><b>success stores</b> -- not just "returned true". A branch that skipped the STLXR would still
 *       report success while leaving memory untouched.</li>
 *   <li><b>failure does NOT store</b> -- the comparison-failure path must leave the word alone. A b.ne
 *       landing past the CLREX would fall into the success tail and write anyway.</li>
 *   <li><b>failure answers false</b>, and a run of them still leaves the original value.</li>
 *   <li><b>a second CAS after a failure still works</b> -- the arm that would catch a MISSING CLREX, since a
 *       stale exclusive monitor is only observable on a LATER store attempt.</li>
 *   <li><b>a full word round-trips</b> -- 0xFEEDFACECAFEBEEF, so a 32-bit compare or store (the wrong size
 *       field) truncates visibly rather than passing on small values.</li>
 * </ul>
 */
public final class CasDemo
{
    public static void main(String[] args)
    {
        long cell = Magic.addrOf(new long[2]) + 24L;    // a heap word we own

        Magic.store64(cell, 10L);
        boolean ok = Magic.cas64(cell, 10L, 20L);
        System.out.println("  cas match     = " + ok + " (want true)");
        System.out.println("  value after   = " + Magic.load64(cell) + " (want 20)");

        boolean bad = Magic.cas64(cell, 999L, 30L);
        System.out.println("  cas mismatch  = " + bad + " (want false)");
        System.out.println("  value kept    = " + Magic.load64(cell) + " (want 20)");

        // AFTER a failed CAS: a stale exclusive monitor shows up only here.
        boolean again = Magic.cas64(cell, 20L, 40L);
        System.out.println("  cas after miss= " + again + " (want true)");
        System.out.println("  value after   = " + Magic.load64(cell) + " (want 40)");

        // A full 64-bit value: a 32-bit compare or store truncates visibly.
        Magic.store64(cell, 0xFEEDFACECAFEBEEFL);
        boolean wide = Magic.cas64(cell, 0xFEEDFACECAFEBEEFL, 0x0123456789ABCDEFL);
        System.out.println("  cas wide      = " + wide + " (want true)");
        System.out.println("  wide after    = " + Long.toHexString(Magic.load64(cell)) + " (want 123456789abcdef)");

        // A mismatch differing ONLY in the high 32 bits: a 32-bit compare would wrongly report a match.
        Magic.store64(cell, 0x1111111100000005L);
        boolean hi = Magic.cas64(cell, 0x2222222200000005L, 7L);
        System.out.println("  cas high-diff = " + hi + " (want false)");
        System.out.println("  high kept     = " + Long.toHexString(Magic.load64(cell)) + " (want 1111111100000005)");

        System.out.println("CasDemo done");
    }
}
