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

import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * java.text date formatting, arm by arm, against a host control run with {@code -Duser.timezone=GMT}: the
 * locale provider arc's second increment. Every arm is guarded, so a failure prints and the probe goes on;
 * a DENYLIST TRAP halts the program, so the arms most likely to reach a denied class come last.
 */
public final class DateFormatProbe
{
    private static final long FIXED = 1729771200000L;   // 2024-10-24T12:00:00Z, a Thursday

    public static void main(String[] args)
    {
        TimeZone gmt = TimeZone.getTimeZone("GMT");
        arm("symbols months[0]", () -> DateFormatSymbols.getInstance(Locale.US).getMonths()[0]);
        arm("symbols weekdays[5]", () -> DateFormatSymbols.getInstance(Locale.US).getWeekdays()[5]);
        arm("symbols ampm", () -> String.join("/", DateFormatSymbols.getInstance(Locale.US).getAmPmStrings()));
        arm("%tB %<tA %<tY", () -> String.format(Locale.US, "%tB %<tA %<tY", FIXED));
        arm("%tF %<tT", () ->
        {
            Calendar c = new GregorianCalendar(gmt, Locale.US);
            c.setTimeInMillis(FIXED);
            return String.format(Locale.US, "%tF %<tT", c);
        });
        arm("sdf iso", () -> fmt("yyyy-MM-dd'T'HH:mm:ss", gmt).format(new Date(FIXED)));
        arm("sdf names", () -> fmt("EEEE, MMMM d, yyyy h:mm a", gmt).format(new Date(FIXED)));
        arm("sdf zone", () -> fmt("HH:mm z Z", gmt).format(new Date(FIXED)));
        arm("sdf parse", () -> String.valueOf(fmt("yyyy-MM-dd HH:mm", gmt).parse("2024-10-24 12:00").getTime()));
        arm("sdf toPattern", () -> fmt("dd/MM/yy", gmt).toPattern());
        arm("getDateInstance LONG", () -> styled(DateFormat.getDateInstance(DateFormat.LONG, Locale.US), gmt));
        arm("getDateInstance SHORT", () -> styled(DateFormat.getDateInstance(DateFormat.SHORT, Locale.US), gmt));
        arm("getDateTimeInstance MEDIUM", () ->
                styled(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US), gmt));
        System.out.println("DateFormatProbe done");
    }

    private static SimpleDateFormat fmt(String pattern, TimeZone zone)
    {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(zone);
        return f;
    }

    private static String styled(DateFormat f, TimeZone zone)
    {
        f.setTimeZone(zone);
        return f.format(new Date(FIXED));
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
