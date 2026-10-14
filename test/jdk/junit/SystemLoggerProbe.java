/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * {@code System.getLogger}: the stock {@code jdk.internal.logger} chain, which this VM denied. With no
 * {@code java.logging} module the JDK's default finder answers a {@code SimpleConsoleLogger}. The logger's NAME,
 * which levels are loggable, and three {@code log()} calls (one below the threshold, one with {@code {0}}
 * parameters) are printed. All depend on the VM being BOOTED: before boot the JDK hands out a BootstrapLogger,
 * which queues records and answers OFF as loggable. Prints values only; the host diff is the gate (the host runs
 * with {@code --limit-modules java.base -Duser.timezone=GMT}, so it takes the same default-finder path -- with
 * java.logging present, OFF reads differently).
 */
public class SystemLoggerProbe
{
    public static void main(String[] args)
    {
        System.Logger log = System.getLogger("probe.logger");
        System.out.println("name=" + log.getName());
        System.Logger.Level[] levels = System.Logger.Level.values();
        for (int i = 0; i < levels.length; i += 1)
        {
            System.out.println(levels[i] + " loggable=" + log.isLoggable(levels[i]));
        }

        // log() itself. SimpleConsoleLogger writes a two-line record to System.err: a header stamped by
        // ZonedDateTime.now() through Formatter's %t conversions (month name, 12-hour clock, AM/PM) followed by the
        // caller found by StackWalker, then "LEVEL: message". The clock differs between runs (and the metal has no
        // wall clock), so the header prints its SHAPE and caller; the message line prints verbatim.
        PrintStream saved = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        log.log(System.Logger.Level.INFO, "hello from the metal");
        log.log(System.Logger.Level.WARNING, "x={0} y={1}", 5, "five");
        log.log(System.Logger.Level.DEBUG, "below the threshold");
        System.setErr(saved);
        String[] lines = captured.toString().split(System.lineSeparator());
        System.out.println("captured lines=" + lines.length);
        for (int i = 0; i < lines.length; i += 1)
        {
            if (i % 2 == 0)
            {
                boolean shape = lines[i].matches("[A-Z][a-z]{2} \\d{2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M .*");
                String[] words = lines[i].split(" ");
                System.out.println("header shape=" + shape + " caller=" + words[words.length - 2] + " "
                        + words[words.length - 1]);
            }
            else
            {
                System.out.println("record=" + lines[i]);
            }
        }
        System.out.println("second lookup non-null=" + (System.getLogger("probe.logger") != null));
        System.out.println("SystemLoggerProbe done");
    }
}
