/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-29
 */
import java.util.regex.Pattern;

/** Isolate the \p{Cntrl} compile that kills the JUnit failure path, in a closure QEMU can run in a minute. */
public class PatternProbe {
    private static void t(String what, Runnable r) {
        try { r.run(); System.out.println("  " + what + " ok"); }
        catch (Throwable e) { System.out.println("  " + what + " -> " + e.getClass().getName()); }
    }

    public static void main(String[] args) {
        System.out.println("pattern probe:");
        t("compile(\"abc\")        ", () -> Pattern.compile("abc"));
        t("compile(\"[a-z]\")      ", () -> Pattern.compile("[a-z]"));
        t("compile(\"\\\\s\")         ", () -> Pattern.compile("\\s"));
        t("compile(\"\\\\p{Cntrl}\")  ", () -> Pattern.compile("\\p{Cntrl}"));
        t("compile(\"\\\\p{Cntrl}\",256)", () -> Pattern.compile("\\p{Cntrl}", 256));
        t("compile(\"\\\\p{Alpha}\")  ", () -> Pattern.compile("\\p{Alpha}"));
    }
}
