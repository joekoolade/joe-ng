/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-29
 */

/**
 * A guest program compiled against the REAL {@code java.base}, so its call sites carry STOCK DESCRIPTORS.
 *
 * <p>WHY THIS IS COMPILED DIFFERENTLY FROM EVERY OTHER PROBE, and why that is the whole point. Every file in
 * {@code JDKTESTS} is compiled with {@code --patch-module java.base=guestsrc}, so javac resolves each call
 * against the OVERLAY -- and where the overlay is missing a member, javac quietly finds another way. A
 * {@code sb.append(1.5)} whose {@code append(double)} does not exist AUTOBOXES to {@code append(Object)} and
 * produces the right answer, so a guest-source probe CANNOT SEE the gap. This file is compiled with no
 * patch-module, so javac emits {@code append:(D)} -- the descriptor a pre-compiled caller really uses.
 *
 * <p>WHAT POPULATION THIS STANDS IN FOR: everything the VM demand-loads that was NOT compiled against
 * guestsrc -- the stock {@code java.base} classes in the image, and every class in the RAMFS jars. picocli's
 * {@code CommandLine$Model$UsageMessageSpec} contains a real {@code invokevirtual
 * StringBuilder.append:(D)} (measured with {@code javap}); it builds an IllegalArgumentException message,
 * which is the worst place to lose a member, because an exception raised while REPORTING a failure replaces
 * the failure with itself.
 *
 * <p>THE FAILURE MODE IT GATES IS THIS PROJECT'S MOST-REPEATED ONE. An overlay WINS the name, so a stock
 * member it does not declare ceases to exist; the call then resolves nowhere and surfaces as a
 * {@code DENYLIST TRAP} with an EMPTY callee and {@code TRAPWIRE index=-1}, blaming a denylist the class is
 * not on. {@code make overlaycheck} finds those by SCANNING; nothing EXECUTED one until this file. This file
 * counts instances of that trap up to a TWELFTH.
 *
 * <p>DELIBERATELY TINY. It names only members whose absence it is meant to catch. Every line added here is
 * compiled against the real JDK and so may reference something this VM genuinely lacks -- so it grows one
 * measured member at a time, never speculatively.
 */
public class StockDescProbe
{
    public static void main(String[] args)
    {
        // append:(D) and append:(F) -- the double form is what picocli calls.
        StringBuilder sb = new StringBuilder();
        sb.append("d=").append(2.5).append(" f=").append(0.5f);
        System.out.println("  append  = [" + sb.toString() + "]");

        // insert:(ID) and insert:(IF) -- taken in the same pass, so a future caller cannot trip them.
        System.out.println("  insertD = [" + new StringBuilder("ab").insert(1, 7.25).toString() + "]");
        System.out.println("  insertF = [" + new StringBuilder("ab").insert(1, 0.5f).toString() + "]");

        System.out.println("StockDescProbe done");
    }
}
