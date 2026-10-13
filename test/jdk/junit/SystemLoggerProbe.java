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
/**
 * {@code System.getLogger}: the stock {@code jdk.internal.logger} chain, which this VM denied. With no
 * {@code java.logging} module the JDK's default finder answers a {@code SimpleConsoleLogger}. The logger's NAME and
 * which levels are loggable are printed. Both depend on the VM being BOOTED: before boot the JDK hands out a
 * BootstrapLogger, which queues records and answers OFF as loggable. Prints values only; the host diff is the
 * gate (the host runs with {@code --limit-modules java.base}, so it takes the same default-finder path -- with
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

        // NOT here yet: log() itself. SimpleConsoleLogger formats a record with java.text.MessageFormat ({0}
        // parameters) and stamps it with ZonedDateTime.now() through Formatter's %t conversions -- java.time's zone
        // rules and their tzdb data, both still denied. That traps loudly today; it is the next arc (STATUS.md).
        System.out.println("second lookup non-null=" + (System.getLogger("probe.logger") != null));
        System.out.println("SystemLoggerProbe done");
    }
}
