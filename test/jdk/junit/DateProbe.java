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

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * java.util.Date / GregorianCalendar / TimeZone, arm by arm, against a host control run with
 * {@code -Duser.timezone=GMT} (this VM's default zone is GMT: TimeZone_md finds no zone source here).
 *
 * <p>WHY: these classes are built on sun/util/calendar, which the image DENIES -- so each arm names the first
 * place a common date API stops working, instead of one trap halting the program. Every arm is guarded so a
 * failure prints the exception and the probe carries on.
 */
public final class DateProbe
{
    public static void main(String[] args)
    {
        arm("epoch getTime", () -> String.valueOf(new Date(0L).getTime()));
        arm("default zone id", () -> TimeZone.getDefault().getID());
        arm("GMT offset", () -> String.valueOf(TimeZone.getTimeZone("GMT").getRawOffset()));
        arm("calendar fields", () ->
        {
            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
            c.setTimeInMillis(1729771200000L);
            return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH)
                    + " " + c.get(Calendar.HOUR_OF_DAY) + ":" + c.get(Calendar.MINUTE)
                    + " dow=" + c.get(Calendar.DAY_OF_WEEK) + " doy=" + c.get(Calendar.DAY_OF_YEAR);
        });
        arm("calendar set/add", () ->
        {
            Calendar c = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
            c.clear();
            c.set(2024, Calendar.FEBRUARY, 28, 23, 30, 0);
            c.add(Calendar.HOUR_OF_DAY, 1);
            return c.get(Calendar.MONTH) + 1 + "/" + c.get(Calendar.DAY_OF_MONTH) + " " + c.getTimeInMillis();
        });
        arm("leap year", () -> new GregorianCalendar().isLeapYear(2024) + " " + new GregorianCalendar().isLeapYear(1900));
        arm("Calendar.getInstance", () ->
        {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            c.setTimeInMillis(0L);
            return String.valueOf(c.get(Calendar.YEAR));
        });
        arm("zone by id", () -> TimeZone.getTimeZone("America/Chicago").getID());
        arm("compareTo/before", () -> new Date(1L).compareTo(new Date(2L)) + " " + new Date(1L).before(new Date(2L)));
        // LAST, because a DENYLIST TRAP halts the program: Date.toString names the zone through
        // TimeZone.getDisplayName -> sun/util/locale/provider/TimeZoneNameUtility (locale data).
        arm("epoch toString", () -> new Date(0L).toString());
        arm("fixed toString", () -> new Date(1729771200000L).toString());
        System.out.println("DateProbe done");
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
