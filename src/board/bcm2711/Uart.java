package board.bcm2711;

import magic.Magic;

/**
 * Mini-UART (AUX / UART1) console driver, written as ordinary Java and compiled
 * to A64 by our baseline compiler. Splitting the boot's UART logic into real
 * methods (called across classes from {@code vm.VM}) is the M2 exercise: genuine
 * {@code BL} calls with arguments and returns, laid out and relocated by the
 * writer (PLAN.md §4 M2).
 */
public final class Uart
{
    private Uart() {}

    /** Bring up the mini-UART: GPIO14/15 → ALT5, 8-bit, clear FIFOs, enable tx/rx. */
    public static void init()
    {
        Magic.store32(Bcm2711.AUX_ENABLES, 1);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IER_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_LCR_REG, 3);
        Magic.store32(Bcm2711.AUX_MU_MCR_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IIR_REG, 0xC6);
        Magic.store32(Bcm2711.AUX_MU_BAUD_REG, baudDivisor());
        Magic.store32(Bcm2711.GPFSEL1, (Bcm2711.ALT5 << 12) | (Bcm2711.ALT5 << 15));
        Magic.store32(Bcm2711.GPIO_PUP_PDN_CNTRL_REG0, 0);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 3);
    }

    /**
     * Baud divisor for 115200, computed from the core clock the firmware reports:
     * mini-UART baud = core_clock / (8*(divisor+1)), so divisor = hz/921600 - 1.
     * The core clock is not something we can assume — it differed across firmware
     * builds and even SD cards on the same board — so we ask rather than guess. If
     * the mailbox does not answer (or reports an implausible rate) we fall back to
     * the compiled-in {@link Bcm2711#BAUD_115200}.
     */
    private static int baudDivisor()
    {
        int hz = Mailbox.coreClockHz();
        coreHz = hz;                                     // kept so boot can report it
        if (hz < 10_000_000 || hz > 1_500_000_000)
        {
            return Bcm2711.BAUD_115200;
        }
        return hz / (8 * 115200) - 1;
    }

    /** Core clock the mailbox reported at init, in Hz (0 = no answer, divisor fell back). */
    public static int coreHz;

    /**
     * Write one byte, translating LF to CRLF. A raw serial console (screen/minicom)
     * treats a bare {@code \n} as line-feed only — no column reset — so without the
     * carriage return each line staircases further right. Emitting {@code \r\n} is
     * the convention for serial output.
     */
    public static void putc(int c)
    {
        if (c == 0x0A)
        {
            putRaw(0x0D);
        }
        putRaw(c);
    }

    /** Write one raw byte, spinning until the TX FIFO can accept it (LSR bit5). */
    private static void putRaw(int c)
    {
        while ((Magic.load32(Bcm2711.AUX_MU_LSR_REG) & 0x20) == 0)
        {
        }
        Magic.store8(Bcm2711.AUX_MU_IO_REG, c);
    }

    // ----- console lock ---------------------------------------------------------------------------
    // With four cores scheduling, two of them can print at once and the bytes interleave -- which is
    // survivable for a log line and ruinous for a fault report, the one output that has to be readable.
    // A report takes the console for its whole multi-call sequence. Ownership is by CORE and the lock is
    // recursive, so a reporter that locks once still calls write()/putc() inside. Armed only while more
    // than one core schedules: LDAXR/STLXR needs the cacheable MMU map that early boot does not have.

    /** Raw scratch word, 0 = free. A different cache line from the scheduler's and the job demo's locks. */
    private static final long UART_LOCK = 0x0302_0080L;
    private static int lockArmed;                        // 1 once more than one core can print
    private static int lockOwner;                        // core holding the console (armLock sets -1 = free;
                                                         //   no initialiser -- that would need a <clinit>,
                                                         //   which runs later than Uart.init())
    private static int lockDepth;

    /** Free the lock word (raw scratch RAM -- nothing else zeroes it) and arm or disarm cross-core locking. */
    public static void armLock(int on)
    {
        Magic.store32(UART_LOCK, 0);
        lockOwner = -1;
        lockDepth = 0;
        lockArmed = on;
    }

    /** Take the console for one whole message. Recursive for this core; a no-op before the lock is armed. */
    public static void lock()
    {
        if (lockArmed == 0)
        {
            return;
        }
        int core = (int) (Magic.readMPIDR() & 3L);
        if (lockOwner == core)
        {
            lockDepth += 1;
            return;
        }
        Magic.spinLock(UART_LOCK);
        lockOwner = core;
        lockDepth = 1;
    }

    /** Release one level of the console lock. */
    public static void unlock()
    {
        if (lockArmed == 0 || lockOwner != (int) (Magic.readMPIDR() & 3L))
        {
            return;
        }
        lockDepth -= 1;
        if (lockDepth <= 0)
        {
            lockOwner = -1;
            Magic.spinUnlock(UART_LOCK);
        }
    }

    /** Write every byte of {@code s} (a real heap byte[], e.g. an interned string literal). */
    public static void write(byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            putc(s[i]);
            i = i + 1;
        }
    }
}
