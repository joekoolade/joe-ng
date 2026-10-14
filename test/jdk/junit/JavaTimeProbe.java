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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The fixed-offset core of {@code java.time}: date arithmetic, the ISO calendar, offsets, instants, durations,
 * {@code DateTimeFormatter} with numeric patterns, {@code Formatter}'s {@code %t} over a temporal, and the
 * clock-reading {@code now()} forms. Every value printed is DETERMINISTIC (fixed inputs); the clock reads print
 * only that they answered. Named zones (tzdb) are deliberately not here. Host control:
 * {@code java -Duser.timezone=GMT} (what an unconfigured Linux host answers); the host diff is the gate.
 */
public class JavaTimeProbe
{
    public static void main(String[] args)
    {
        LocalDate d = LocalDate.of(2026, 10, 13);
        System.out.println("date=" + d + " dow=" + d.getDayOfWeek() + " doy=" + d.getDayOfYear());
        System.out.println("plus30=" + d.plusDays(30) + " minus1y=" + d.minusYears(1) + " leap=" + d.isLeapYear());
        System.out.println("feb29+1y=" + LocalDate.of(2024, 2, 29).plusYears(1));
        System.out.println("days to 2027=" + ChronoUnit.DAYS.between(d, LocalDate.of(2027, 1, 1)));
        System.out.println("next monday=" + d.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY)));

        LocalDateTime ldt = LocalDateTime.of(2026, 10, 13, 22, 8, 1, 123_000_000);
        System.out.println("ldt=" + ldt + " t=" + LocalTime.of(7, 5));
        Instant i = Instant.ofEpochSecond(1_700_000_000L, 42);
        System.out.println("instant=" + i + " ms=" + i.toEpochMilli());
        System.out.println("utc=" + ZonedDateTime.ofInstant(i, ZoneOffset.UTC));
        ZoneOffset cdt = ZoneOffset.ofHours(-5);
        OffsetDateTime odt = OffsetDateTime.of(ldt, cdt);
        System.out.println("odt=" + odt + " instant=" + odt.toInstant());
        System.out.println("offset id=" + cdt.getId() + " secs=" + cdt.getTotalSeconds());

        Duration dur = Duration.between(LocalDateTime.of(2026, 1, 1, 0, 0), ldt);
        System.out.println("dur=" + dur + " hours=" + dur.toHours());

        System.out.println("iso=" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ldt));
        System.out.println("pattern=" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(ldt));
        System.out.println("parse=" + LocalDate.parse("2026-12-25").getDayOfWeek());
        System.out.println("fmt=" + String.format("%tY-%<tm-%<td %<tH:%<tM:%<tS", ldt));

        System.out.println("zone=" + ZoneId.systemDefault().getId());
        Instant now = Instant.now();
        System.out.println("now answered=" + (now != null) + " zdt answered=" + (ZonedDateTime.now() != null));
        System.out.println("JavaTimeProbe done");
    }
}
