/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The wall clock. The time itself differs between any two runs, so every arm prints a FACT about it that a
 * correct clock answers the same way everywhere: that it is no earlier than a fixed date this source postdates,
 * that {@code currentTimeMillis}, {@code Instant.now}, {@code Clock.systemUTC} and {@code new Date()} are one
 * clock, that it does not run backwards, and that the current time survives a zip entry (whose DOS date field
 * cannot hold a year before 1980) and a file's modification time. Values only; the host diff is the gate.
 *
 * <p>Pass a writable directory as the first argument to run the file arm; without one it is skipped.
 */
public class WallClockProbe
{
    /** 2026-10-01T00:00:00Z: this file was written after it, so no correct clock running it reads earlier. */
    static final long FLOOR_MS = 1790812800000L;

    interface Arm
    {
        Object call() throws Exception;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            System.out.println(what + " -> " + a.call());
        }
        catch (Exception e)
        {
            System.out.println(what + " -> " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception
    {
        arm("currentTimeMillis >= 2026-10-01", () -> System.currentTimeMillis() >= FLOOR_MS);
        arm("year of now >= 2026", () -> LocalDate.now(ZoneOffset.UTC).getYear() >= 2026);
        arm("Instant.now within 2 s of currentTimeMillis", () ->
        {
            long ms = System.currentTimeMillis();
            long in = Instant.now().toEpochMilli();
            return Math.abs(in - ms) < 2000L;
        });
        arm("Clock.systemUTC within 2 s of currentTimeMillis", () ->
        {
            long ms = System.currentTimeMillis();
            long c = Clock.systemUTC().millis();
            return Math.abs(c - ms) < 2000L;
        });
        arm("new Date() within 2 s of currentTimeMillis", () ->
        {
            long ms = System.currentTimeMillis();
            long d = new Date().getTime();
            return Math.abs(d - ms) < 2000L;
        });
        arm("Instant.now nanos in range", () ->
        {
            int n = Instant.now().getNano();
            return n >= 0 && n < 1000000000;
        });
        arm("never backwards over 2000 reads", () ->
        {
            long prev = System.currentTimeMillis();
            int i = 0;
            while (i < 2000)
            {
                long t = System.currentTimeMillis();
                if (t < prev)
                {
                    return "went back " + (prev - t) + " ms";
                }
                prev = t;
                i += 1;
            }
            return "monotone";
        });
        arm("Thread.sleep(50) advances it by 40..5000 ms", () ->
        {
            long a = System.currentTimeMillis();
            Thread.sleep(50);
            long b = System.currentTimeMillis();
            return b - a >= 40L && b - a < 5000L;
        });
        arm("zip entry keeps the current time to 2 s", () ->
        {
            long now = System.currentTimeMillis();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(bo))
            {
                ZipEntry e = new ZipEntry("a.txt");
                e.setTime(now);
                zo.putNextEntry(e);
                zo.write('x');
                zo.closeEntry();
            }
            try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(bo.toByteArray())))
            {
                ZipEntry e = zi.getNextEntry();
                long back = e.getTime();
                return Math.abs(back - now) <= 2000L;
            }
        });
        arm("zip entry written without setTime is dated >= 2026", () ->
        {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(bo))
            {
                zo.putNextEntry(new ZipEntry("b.txt"));
                zo.write('y');
                zo.closeEntry();
            }
            try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(bo.toByteArray())))
            {
                ZipEntry e = zi.getNextEntry();
                return e.getTime() >= FLOOR_MS;
            }
        });
        if (args.length > 0)
        {
            arm("new file's mtime within 5 s of now", () ->
            {
                Path p = Path.of(args[0], "wallclock.txt");
                Files.deleteIfExists(p);
                long now = System.currentTimeMillis();
                Files.writeString(p, "t");
                FileTime ft = Files.getLastModifiedTime(p);
                Files.delete(p);
                return Math.abs(ft.toMillis() - now) <= 5000L;
            });
        }
        else
        {
            System.out.println("file arm skipped (no directory given)");
        }
        System.out.println("WallClockProbe done");
    }
}
