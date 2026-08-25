package java.util.zip;

import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;

/**
 * A JDK-free {@code java.util.zip.ZipUtils} overlay (wins by name) — the package-private byte and timestamp
 * helpers the stock {@code ZipEntry}/{@code ZipInputStream} are built on. Two things make the stock class
 * unusable on metal: its accessors read through {@code Unsafe.getIntUnaligned}, and its {@code <clinit>} binds
 * {@code SharedSecrets.getJavaNioAccess()} plus a {@code ByteBuffer}, neither of which exists here. This
 * overlay is plain byte arithmetic with no static state at all, so the UNMODIFIED stock zip classes above it
 * run unchanged.
 *
 * <p>Timestamps: joe-ng has no timezone database, so the MS-DOS date fields are interpreted as UTC rather than
 * local time (stock uses {@code ZoneId.systemDefault()}). The {@code FileTime}/{@code LocalDateTime} returning
 * helpers exist only to satisfy the stock {@code ZipEntry}'s call sites and answer null — those types live
 * under denylisted packages, and nothing in the read path consumes their results.
 */
class ZipUtils
{
    /** The value {@code ZipEntry.xdostime} carries when a timestamp predates the DOS epoch. */
    public static final long WINDOWS_TIME_NOT_AVAILABLE = Long.MIN_VALUE;

    public static final long UPPER_UNIXTIME_BOUND = 0x7fffffff;

    private ZipUtils()
    {
    }

    /** The unsigned 16-bit little-endian value at {@code b[off]}. */
    public static final int get16(byte[] b, int off)
    {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    /** The unsigned 32-bit little-endian value at {@code b[off]}. */
    public static final long get32(byte[] b, int off)
    {
        return get32S(b, off) & 0xffffffffL;
    }

    /** The signed 32-bit little-endian value at {@code b[off]}. */
    public static final int get32S(byte[] b, int off)
    {
        return get16(b, off) | (get16(b, off + 2) << 16);
    }

    /** The signed 64-bit little-endian value at {@code b[off]}. */
    public static final long get64S(byte[] b, int off)
    {
        return (get32(b, off)) | ((long) get32S(b, off + 4) << 32);
    }

    /** Store the low 16 bits of {@code value} little-endian at {@code b[off]}. */
    public static final void put16(byte[] b, int off, int value)
    {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >> 8);
    }

    /** Store {@code value} as 32 bits little-endian at {@code b[off]}. */
    public static final void put32(byte[] b, int off, int value)
    {
        put16(b, off, value & 0xffff);
        put16(b, off + 2, (value >> 16) & 0xffff);
    }

    /**
     * The extended DOS time {@code ZipEntry.xdostime} holds — the packed DOS date in the low 32 bits, plus a
     * millisecond remainder in the high 32 — converted to milliseconds since the Java epoch.
     */
    public static long extendedDosToJavaTime(long xdostime)
    {
        return dosToJavaTime(xdostime) + (xdostime >> 32);
    }

    /** Milliseconds since the Java epoch for a packed MS-DOS date/time, read as UTC. */
    public static long dosToJavaTime(long dtime)
    {
        int year = (int) ((dtime >> 25) & 0x7f) + 1980;
        int month = (int) ((dtime >> 21) & 0x0f);
        int day = (int) ((dtime >> 16) & 0x1f);
        int hour = (int) ((dtime >> 11) & 0x1f);
        int minute = (int) ((dtime >> 5) & 0x3f);
        int second = (int) ((dtime << 1) & 0x3e);
        if (month < 1 || month > 12 || day < 1)
        {
            return 0L;                                 // a zeroed DOS field: no timestamp recorded
        }
        long days = daysFromCivil(year, month, day);
        return ((days * 24L + hour) * 60L + minute) * 60_000L + second * 1000L;
    }

    /** Days since 1970-01-01 for a proleptic-Gregorian date (Howard Hinnant's days-from-civil). */
    private static long daysFromCivil(int year, int month, int day)
    {
        int y = year;
        if (month <= 2)
        {
            y -= 1;
        }
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int mp = month + (month > 2 ? -3 : 9);
        int doy = (153 * mp + 2) / 5 + day - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097L + doe - 719468L;
    }

    /** Write side: joe-ng reads archives, so a Java time never has to become a DOS time. */
    public static long javaToExtendedDosTime(long time)
    {
        return WINDOWS_TIME_NOT_AVAILABLE;
    }

    /** Present only for the stock {@code ZipEntry}'s write path, which the read path never enters. */
    static LocalDateTime javaEpochToLocalDateTime(long time)
    {
        return null;
    }

    /** Extended-timestamp extra fields resolve to no {@code FileTime}: the DOS time is the timestamp here. */
    public static final FileTime unixTimeToFileTime(long utime)
    {
        return null;
    }

    /** As {@link #unixTimeToFileTime}, for the NTFS-timestamp extra field. */
    public static final FileTime winTimeToFileTime(long wtime)
    {
        return null;
    }

    public static final long fileTimeToUnixTime(FileTime ftime)
    {
        return 0L;
    }

    public static final long fileTimeToWinTime(FileTime ftime)
    {
        return WINDOWS_TIME_NOT_AVAILABLE;
    }
}
