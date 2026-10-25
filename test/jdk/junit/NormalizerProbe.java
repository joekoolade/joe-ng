/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-25
 */

import java.text.Bidi;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * java.text.Normalizer and java.text.Bidi, arm by arm, against the host: both run on jdk/internal/icu and its
 * java.base data files (nfc.nrm, nfkc.nrm, uprops.icu, ubidi.icu), which used to be denied. Each result is
 * printed as code points, so a normalization difference is visible rather than rendered away.
 */
public final class NormalizerProbe
{
    public static void main(String[] args)
    {
        String composed = "été Ångström ﬁ ①";
        arm("NFD", () -> cps(Normalizer.normalize(composed, Normalizer.Form.NFD)));
        arm("NFC of NFD", () -> cps(Normalizer.normalize(Normalizer.normalize(composed, Normalizer.Form.NFD),
                Normalizer.Form.NFC)));
        arm("NFKD", () -> cps(Normalizer.normalize(composed, Normalizer.Form.NFKD)));
        arm("NFKC", () -> cps(Normalizer.normalize(composed, Normalizer.Form.NFKC)));
        arm("isNormalized NFC/NFD", () -> Normalizer.isNormalized(composed, Normalizer.Form.NFC) + " "
                + Normalizer.isNormalized(composed, Normalizer.Form.NFD));
        arm("strip accents", () -> Normalizer.normalize("Crème Brûlée", Normalizer.Form.NFD)
                .replaceAll("\\p{M}", ""));
        arm("hangul NFD", () -> cps(Normalizer.normalize("가", Normalizer.Form.NFD)));
        arm("regex CANON_EQ", () -> String.valueOf(
                Pattern.compile("é", Pattern.CANON_EQ).matcher("é").matches()));
        arm("bidi LTR", () -> bidi("hello"));
        arm("bidi RTL", () -> bidi("שלום"));
        arm("bidi mixed", () -> bidi("abc אבג def"));
        arm("requiresBidi", () -> Bidi.requiresBidi("abc".toCharArray(), 0, 3) + " "
                + Bidi.requiresBidi("א".toCharArray(), 0, 1));
        System.out.println("NormalizerProbe done");
    }

    private static String bidi(String s)
    {
        Bidi b = new Bidi(s, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        StringBuilder sb = new StringBuilder();
        sb.append("ltr=").append(b.isLeftToRight()).append(" mixed=").append(b.isMixed())
          .append(" runs=").append(b.getRunCount());
        for (int i = 0; i < b.getRunCount(); i++)
        {
            sb.append(" [").append(b.getRunStart(i)).append(',').append(b.getRunLimit(i))
              .append(" L").append(b.getRunLevel(i)).append(']');
        }
        return sb.toString();
    }

    private static String cps(String s)
    {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(c -> sb.append(Integer.toHexString(c)).append(' '));
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
