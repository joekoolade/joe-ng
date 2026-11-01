/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-31
 */

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.TimeZone;

/**
 * sun/util/ is no longer denied. The narrowings had already opened locale, cldr, resources, spi, logging, calendar
 * and PreHashedMap; the three classes left behind the prefix are each on an ordinary java.util path:
 * ResourceBundleEnumeration (getKeys on a ListResourceBundle or PropertyResourceBundle, merging the parent's keys),
 * PropertyResourceBundleCharset (new PropertyResourceBundle(InputStream): UTF-8 first, ISO-8859-1 on a malformed
 * byte) and BuddhistCalendar (the "buddhist" calendar type). Each arm runs on its own and prints what it got.
 */
public class SunUtilProbe
{
    interface Arm
    {
        String run() throws Throwable;
    }

    static void report(String what, Arm arm)
    {
        String got;
        try
        {
            got = arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName();
        }
        System.out.println(what + ": " + got);
    }

    public static class Parent extends ListResourceBundle
    {
        protected Object[][] getContents()
        {
            return new Object[][] { { "a", "pa" }, { "b", "pb" } };
        }
    }

    public static class Child extends ListResourceBundle
    {
        Child()
        {
            setParent(new Parent());
        }

        protected Object[][] getContents()
        {
            return new Object[][] { { "b", "cb" }, { "c", "cc" } };
        }
    }

    static String keys(Enumeration<String> e)
    {
        List<String> all = new ArrayList<>();
        while (e.hasMoreElements())
        {
            all.add(e.nextElement());
        }
        Collections.sort(all);
        return all.toString();
    }

    public static void main(String[] args)
    {
        report("list bundle keys", () ->
        {
            Child c = new Child();
            Enumeration<String> e = c.getKeys();
            return keys(e) + " b=" + c.getString("b") + " a=" + c.getString("a") + " via="
                    + e.getClass().getName();
        });
        report("properties utf-8", () ->
        {
            byte[] in = "greet=grüß dich\nn=1\n".getBytes(StandardCharsets.UTF_8);
            PropertyResourceBundle b = new PropertyResourceBundle(new ByteArrayInputStream(in));
            String g = b.getString("greet");
            return keys(b.getKeys()) + " greet.length=" + g.length() + " last=" + (int) g.charAt(4);
        });
        report("properties iso-8859-1 fallback", () ->
        {
            byte[] in = { 'k', '=', 'x', (byte) 0xE9, 'y', '\n' };
            PropertyResourceBundle b = new PropertyResourceBundle(new ByteArrayInputStream(in));
            String v = b.getString("k");
            return "k.length=" + v.length() + " mid=" + (int) v.charAt(1);
        });
        report("buddhist calendar", () ->
        {
            Calendar c = new Calendar.Builder().setCalendarType("buddhist")
                    .setTimeZone(TimeZone.getTimeZone("UTC")).setInstant(0L).build();
            return c.getClass().getName() + " type=" + c.getCalendarType() + " year=" + c.get(Calendar.YEAR);
        });
        report("buddhist by locale", () ->
        {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.forLanguageTag("th-TH"));
            c.setTimeInMillis(86400000L * 365);
            return c.getCalendarType() + " year=" + c.get(Calendar.YEAR) + " era=" + c.get(Calendar.ERA);
        });
        System.out.println("SunUtilProbe done");
    }
}
