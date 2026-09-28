/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-28
 */
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Exercises {@code java.util.concurrent.TimeUnit}'s conversion surface and enum surface.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values:
 * compiled against the real JDK the arms reach stock {@code TimeUnit}, compiled against {@code guestsrc} they
 * reached joe-ng's overlay, and every arm must print the same bytes in both worlds. An oracle that cannot be
 * typed wrong removes the whole mis-transcription failure mode.
 *
 * <p>THE SATURATION ARMS ARE THE ONES THAT DISCRIMINATE, and they are why this file exists. The retired
 * overlay computed {@code convert} as {@code d * srcNanos / dstNanos} -- multiply first, no overflow check --
 * where stock SATURATES to {@code Long.MAX_VALUE}/{@code MIN_VALUE}. Measured on the host before this probe
 * was written, four arms differed, and every difference was a wrapped value that still looks like a duration:
 * {@code NANOSECONDS.convert(Long.MAX_VALUE, SECONDS)} answered **-1000000000** and
 * {@code convert(Long.MIN_VALUE, SECONDS)} answered **0**. A negative nanosecond duration handed to a sleep or
 * a timeout means "expire immediately" where the caller asked for "effectively never".
 *
 * <p>AND THE SMALL DOWN-CONVERSIONS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both
 * states is not a control. {@code MILLISECONDS.convert(nanos, NANOSECONDS)} is what joe-ng's own
 * {@code JoinWithDuration}/{@code SleepWithDuration} timing tests call, it cannot overflow at realistic
 * values, and it was correct under the overlay -- which is exactly why the defect survived, the same way
 * {@code Character.isLetter} was correct on ASCII.
 *
 * <p>THE {@code convert(Duration)} ARMS EXIST BECAUSE DELETING THE OVERLAY ARMED A SECOND TRAP. Stock's
 * {@code convert(Duration)} opens {@code duration.getSeconds()} / {@code duration.getNano()}, and joe-ng's
 * {@code java/time/Duration} OVERLAY declared neither -- so with stock TimeUnit shipped, that call would
 * have resolved nowhere and surfaced as a {@code DENYLIST TRAP} naming a list Duration is not on. The two
 * accessors were added with this increment; these arms are what say so rather than a reading.
 *
 * <p>THE NEGATIVE-DURATION ARM IS THE ONE THAT DISCRIMINATES THERE. {@code getNano()} is the
 * nano-of-second and is always 0..999,999,999, so a negative duration BORROWS from the seconds --
 * {@code ofNanos(-1)} is seconds {@code -1}, nano {@code 999999999}. Java's {@code /} truncates toward
 * zero, so an accessor written the obvious way is wrong for exactly the negative half while being right
 * for every positive value anyone would try first. Same shape as the saturation defect above.
 *
 * <p>EVERY Duration ARM STAYS INSIDE ONE {@code long} OF NANOSECONDS, deliberately. The overlay stores a
 * Duration as total nanos where stock carries seconds and nanos separately, so a duration beyond ~292
 * years is representable on the host and not here -- a limit of the DURATION overlay, not of TimeUnit, and
 * a different increment. Arms outside that range would diff for a reason this card is not about.
 */
public final class TimeUnitProbe
{
    public static void main(String[] args)
    {
        System.out.println("timeunit probe:");

        // SATURATION -- the defect. Stock clamps; multiply-then-divide wraps.
        System.out.println("  -- saturation --");
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, Long.MAX_VALUE, TimeUnit.SECONDS);
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, Long.MIN_VALUE, TimeUnit.SECONDS);
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, 10000000000L, TimeUnit.SECONDS);
        conv("NANOSECONDS <- DAYS", TimeUnit.NANOSECONDS, Long.MAX_VALUE, TimeUnit.DAYS);
        conv("MILLISECONDS <- DAYS", TimeUnit.MILLISECONDS, Long.MAX_VALUE, TimeUnit.DAYS);
        conv("MICROSECONDS <- HOURS", TimeUnit.MICROSECONDS, Long.MIN_VALUE, TimeUnit.HOURS);

        // The boundary either side of the clamp: the largest value that does NOT saturate, and the
        // first that does. A fix that clamps too eagerly passes the arms above and fails these.
        System.out.println("  -- the clamp boundary --");
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, 9223372036L, TimeUnit.SECONDS);
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, 9223372037L, TimeUnit.SECONDS);
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, -9223372036L, TimeUnit.SECONDS);
        conv("NANOSECONDS <- SECONDS", TimeUnit.NANOSECONDS, -9223372037L, TimeUnit.SECONDS);

        // ORDINARY conversions, including the ones joe-ng's timing tests use. Controls: these passed
        // under the overlay and must keep passing.
        System.out.println("  -- ordinary (the built-in comparison) --");
        conv("MILLISECONDS <- NANOSECONDS", TimeUnit.MILLISECONDS, 5000000000L, TimeUnit.NANOSECONDS);
        conv("SECONDS <- MILLISECONDS", TimeUnit.SECONDS, 1500L, TimeUnit.MILLISECONDS);
        conv("NANOSECONDS <- DAYS", TimeUnit.NANOSECONDS, 365L, TimeUnit.DAYS);
        conv("NANOSECONDS <- DAYS", TimeUnit.NANOSECONDS, -365L, TimeUnit.DAYS);
        conv("MINUTES <- SECONDS", TimeUnit.MINUTES, 3661L, TimeUnit.SECONDS);
        conv("HOURS <- MINUTES", TimeUnit.HOURS, 1439L, TimeUnit.MINUTES);
        conv("DAYS <- HOURS", TimeUnit.DAYS, 47L, TimeUnit.HOURS);
        conv("SECONDS <- SECONDS", TimeUnit.SECONDS, 42L, TimeUnit.SECONDS);

        // TRUNCATION TOWARD ZERO, both signs -- a conversion that rounded would pass every arm above.
        System.out.println("  -- truncation, both signs --");
        conv("SECONDS <- MILLISECONDS", TimeUnit.SECONDS, 1999L, TimeUnit.MILLISECONDS);
        conv("SECONDS <- MILLISECONDS", TimeUnit.SECONDS, -1999L, TimeUnit.MILLISECONDS);
        conv("SECONDS <- MILLISECONDS", TimeUnit.SECONDS, 999L, TimeUnit.MILLISECONDS);
        conv("SECONDS <- MILLISECONDS", TimeUnit.SECONDS, -999L, TimeUnit.MILLISECONDS);

        // THE CONVENIENCE METHODS are a separate surface from convert() and saturate too.
        System.out.println("  -- toXxx --");
        System.out.println("  DAYS.toNanos(365)          = " + TimeUnit.DAYS.toNanos(365L));
        System.out.println("  DAYS.toNanos(MAX)          = " + TimeUnit.DAYS.toNanos(Long.MAX_VALUE));
        System.out.println("  DAYS.toNanos(MIN)          = " + TimeUnit.DAYS.toNanos(Long.MIN_VALUE));
        System.out.println("  SECONDS.toMillis(MAX)      = " + TimeUnit.SECONDS.toMillis(Long.MAX_VALUE));
        System.out.println("  NANOSECONDS.toMillis(1999999999) = " + TimeUnit.NANOSECONDS.toMillis(1999999999L));
        System.out.println("  MILLISECONDS.toSeconds(-1999) = " + TimeUnit.MILLISECONDS.toSeconds(-1999L));
        System.out.println("  HOURS.toDays(47)           = " + TimeUnit.HOURS.toDays(47L));
        System.out.println("  MINUTES.toHours(1439)      = " + TimeUnit.MINUTES.toHours(1439L));
        System.out.println("  DAYS.toDays(7)             = " + TimeUnit.DAYS.toDays(7L));
        System.out.println("  MICROSECONDS.toNanos(5)    = " + TimeUnit.MICROSECONDS.toNanos(5L));

        // convert(Duration) -- the path that reaches Duration.getSeconds()/getNano().
        System.out.println("  -- convert(Duration) --");
        dur("NANOSECONDS", TimeUnit.NANOSECONDS, Duration.ofNanos(1500000000L));
        dur("MILLISECONDS", TimeUnit.MILLISECONDS, Duration.ofNanos(1500000000L));
        dur("SECONDS", TimeUnit.SECONDS, Duration.ofNanos(1500000000L));
        dur("SECONDS", TimeUnit.SECONDS, Duration.ofSeconds(90L));
        dur("MINUTES", TimeUnit.MINUTES, Duration.ofSeconds(90L));
        dur("MILLISECONDS", TimeUnit.MILLISECONDS, Duration.ofMillis(2500L));
        dur("NANOSECONDS", TimeUnit.NANOSECONDS, Duration.ofNanos(0L));
        // the negative half -- where a truncating getSeconds()/getNano() goes wrong
        dur("NANOSECONDS", TimeUnit.NANOSECONDS, Duration.ofNanos(-1L));
        dur("NANOSECONDS", TimeUnit.NANOSECONDS, Duration.ofNanos(-1500000000L));
        dur("MILLISECONDS", TimeUnit.MILLISECONDS, Duration.ofNanos(-1500000000L));
        dur("SECONDS", TimeUnit.SECONDS, Duration.ofNanos(-1500000000L));
        dur("SECONDS", TimeUnit.SECONDS, Duration.ofSeconds(-90L));
        dur("MINUTES", TimeUnit.MINUTES, Duration.ofSeconds(-90L));
        // the accessors themselves, so a wrong answer names the accessor rather than the conversion
        System.out.println("  ofNanos(-1).getSeconds()   = " + Duration.ofNanos(-1L).getSeconds());
        System.out.println("  ofNanos(-1).getNano()      = " + Duration.ofNanos(-1L).getNano());
        System.out.println("  ofNanos(1).getSeconds()    = " + Duration.ofNanos(1L).getSeconds());
        System.out.println("  ofNanos(1).getNano()       = " + Duration.ofNanos(1L).getNano());
        System.out.println("  ofSeconds(-2).getSeconds() = " + Duration.ofSeconds(-2L).getSeconds());
        System.out.println("  ofSeconds(-2).getNano()    = " + Duration.ofSeconds(-2L).getNano());

        // THE ENUM SURFACE. The overlay was a plain CLASS hand-writing name()/ordinal()/values(); stock is a
        // real enum. `values()[i].ordinal() == i` and valueOf round-tripping are what say the two agree.
        System.out.println("  -- enum surface --");
        TimeUnit[] vs = TimeUnit.values();
        System.out.println("  values().length            = " + vs.length);
        StringBuilder order = new StringBuilder();
        boolean ordinalsOk = true;
        for (int i = 0; i < vs.length; i++)
        {
            order.append(vs[i].name());
            if (i + 1 < vs.length)
            {
                order.append(',');
            }
            if (vs[i].ordinal() != i)
            {
                ordinalsOk = false;
            }
        }
        System.out.println("  values() in order          = " + order);
        System.out.println("  ordinal()==index for all   = " + ordinalsOk);
        System.out.println("  valueOf(\"SECONDS\")         = " + TimeUnit.valueOf("SECONDS"));
        System.out.println("  valueOf round-trips        = " + (TimeUnit.valueOf("HOURS") == TimeUnit.HOURS));
        System.out.println("  toString(MILLISECONDS)     = " + TimeUnit.MILLISECONDS.toString());
        System.out.println("  compareTo(SECONDS,MINUTES) = " + TimeUnit.SECONDS.compareTo(TimeUnit.MINUTES));
        System.out.println("  compareTo(DAYS,NANOSECONDS)= " + TimeUnit.DAYS.compareTo(TimeUnit.NANOSECONDS));
        System.out.println("  NANOSECONDS==values()[0]   = " + (TimeUnit.NANOSECONDS == vs[0]));

        System.out.println("TimeUnitProbe done");
    }

    /** Prints one convert(Duration) arm. No expected value: the host run is the oracle. */
    private static void dur(String what, TimeUnit dst, Duration d)
    {
        System.out.println("  " + what + " convert(Duration " + d.toNanos() + "ns) = " + dst.convert(d));
    }

    /** Prints one convert() arm. No expected value: the host run is the oracle. */
    private static void conv(String what, TimeUnit dst, long d, TimeUnit src)
    {
        System.out.println("  " + what + " convert(" + d + ") = " + dst.convert(d, src));
    }
}
