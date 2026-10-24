/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-24
 */

import java.text.BreakIterator;
import java.util.Locale;

/**
 * java.text.BreakIterator boundaries, arm by arm, against a host control run with {@code --limit-modules java.base}
 * (java.base's own rule data, which is what the image carries). The locale provider arc's fourth increment: the
 * overlay was a hand-written whitespace/hyphen LINE breaker -- these arms pin the stock rule-based boundaries, so a
 * divergence names the iterator kind and the text.
 */
public final class BreakIteratorProbe
{
    private static final String TEXT = "Hello, world! It's a test-case: wrap me. Done?  Yes.";

    public static void main(String[] args)
    {
        arm("line", () -> bounds(BreakIterator.getLineInstance(Locale.US), TEXT));
        arm("word", () -> bounds(BreakIterator.getWordInstance(Locale.US), TEXT));
        arm("sentence", () -> bounds(BreakIterator.getSentenceInstance(Locale.US), TEXT));
        arm("line root", () -> bounds(BreakIterator.getLineInstance(), "a b-c\td"));
        arm("line following(7)", () ->
        {
            BreakIterator b = BreakIterator.getLineInstance(Locale.US);
            b.setText(TEXT);
            return b.following(7) + " " + b.preceding(20) + " " + b.isBoundary(14) + " " + b.last();
        });
        arm("word empty", () -> bounds(BreakIterator.getWordInstance(Locale.US), ""));
        // LAST: the character instance is the grapheme-cluster iterator (jdk/internal/util/regex/Grapheme).
        arm("character", () -> bounds(BreakIterator.getCharacterInstance(Locale.US), "aéo"));
        System.out.println("BreakIteratorProbe done");
    }

    private static String bounds(BreakIterator b, String text)
    {
        b.setText(text);
        StringBuilder sb = new StringBuilder();
        int p = b.first();
        while (p != BreakIterator.DONE)
        {
            sb.append(p).append(' ');
            p = b.next();
        }
        return sb.toString().trim();
    }

    private interface Arm
    {
        String run() throws Exception;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println(what + " = " + r);
    }
}
