package board.bcm2711;

import magic.Magic;
import vm.VM;

/**
 * A standalone SDIO host driver for the on-board WiFi controller (Cypress CYW43455) — the SDIO transport
 * the {@code Cyw43} chip driver rides on (PLAN.md WiFi §M0/M1). It is deliberately SEPARATE from
 * {@link Emmc} (which is load-bearing for the boot's generation counter and must not be destabilised):
 * it copies Emmc's proven SDHCI {@code command}/{@code waitSet}/{@code waitClear} idiom and CMDTM
 * encodings, but adds the SDIO-specific pieces Emmc lacks — CMD5 (IO_SEND_OP_COND), CMD52 (IO_RW_DIRECT,
 * R5), CMD53 (IO_RW_EXTENDED, byte + block PIO), the RESP1-3 registers, a 4-bit bus width, a parameterised
 * block size, and a real clock divider from the mailbox EMMC-clock query.
 *
 * <p>PIO only (no DMA), like Emmc — which sidesteps cache maintenance for the data path. All delays are
 * CNTPCT-based ({@link VM#delayMs}) since driver bring-up runs before/around the scheduler.
 *
 * <p>Source: SDIO Simplified Spec (CMD5/52/53, CCCR), SDHCI spec (register layout), BCM2711 peripherals.
 * See SOURCES.md. Written to bring up the CYW43455; concepts cross-checked against Circle / cyw43-driver.
 */
public final class Sdio
{
    private Sdio() {}

    // SDHCI register offsets from the controller base (same layout as Emmc, plus RESP1-3).
    private static final long BLKSIZECNT = 0x04, ARG1 = 0x08, CMDTM = 0x0C;
    private static final long RESP0 = 0x10, RESP1 = 0x14, RESP2 = 0x18, RESP3 = 0x1C;
    private static final long DATA = 0x20, STATUS = 0x24, CONTROL0 = 0x28, CONTROL1 = 0x2C;
    private static final long INTERRUPT = 0x30, IRPT_MASK = 0x34, IRPT_EN = 0x38;

    // VERIFY (M1, against BCM2711 docs / Circle): the SDHCI block the Pi 4B routes the CYW43455 WiFi SDIO to.
    // The microSD card is on EMMC2 (0xFE340000); the WiFi hangs off the legacy Arasan/EMMC block below.
    static long base = 0xFE30_0000L;

    // WiFi SDIO is bonded to GPIO34..39 in ALT3 (CLK, CMD, DAT0..3).
    private static final int PIN_FIRST = 34, PIN_LAST = 39;

    // CMDTM command encodings: (index<<24) | (rspType<<16) | flags.
    private static final int RSP_NONE = 0, RSP_136 = 1 << 16, RSP_48 = 2 << 16, RSP_48B = 3 << 16;
    private static final int CRC_CHK = 1 << 19, IX_CHK = 1 << 20, IS_DATA = 1 << 21, DAT_READ = 1 << 4;
    private static final int TM_BLKCNT_EN = 1 << 1, TM_MULTI_BLOCK = 1 << 5;

    // INTERRUPT status bits.
    private static final int INT_CMD_DONE = 1, INT_DATA_DONE = 1 << 1;
    private static final int INT_WRITE_RDY = 1 << 4, INT_READ_RDY = 1 << 5, INT_ERR = 1 << 15;
    // STATUS present-state bits.
    private static final int SR_CMD_INHIBIT = 1, SR_DAT_INHIBIT = 1 << 1;

    private static long rca;            // the card's relative address (CMD3), shifted for CMD7
    private static int baseClockHz;     // EMMC base clock from the mailbox (for the divider)

    /**
     * Bring up the SDIO host + enumerate the CYW43455 as an SDIO device: pin mux, host reset, ID clock,
     * CMD5 (op-cond) / CMD3 (RCA) / CMD7 (select), then 4-bit bus. Returns 0 on success, else a negative
     * step code (Emmc convention) naming where it stalled. Function enable + block sizes are the chip
     * driver's job (they touch CCCR); this leaves the card SELECTED in 4-bit mode.
     */
    public static int init()
    {
        Gpio.setAlt(PIN_FIRST, Bcm2711.ALT3);           // CLK
        int p = PIN_FIRST + 1;
        while (p <= PIN_LAST)                            // CMD + DAT0..3 need pull-ups
        {
            Gpio.setAlt(p, Bcm2711.ALT3);
            Gpio.setPull(p, Bcm2711.PULL_UP);
            p = p + 1;
        }

        baseClockHz = Mailbox.getClockRate(Bcm2711.CLOCK_ID_EMMC);
        if (baseClockHz < 10000000 || baseClockHz > 400000000)
        {
            baseClockHz = 100000000;                     // fallback if the mailbox is unhelpful
        }

        Magic.store32(base + CONTROL0, 0);
        or32(base + CONTROL1, 1 << 24);                  // SRST_HC: full host-controller reset
        if (!waitClear(base + CONTROL1, 1 << 24))
        {
            return -2;
        }
        int c1 = (1 << 0) | clockDiv(400000) | (11 << 16);   // internal clock + ~400 kHz ID clock + timeout
        Magic.store32(base + CONTROL1, c1);
        if (!waitSet(base + CONTROL1, 1 << 1))               // CLK_STABLE
        {
            return -3;
        }
        or32(base + CONTROL1, 1 << 2);                       // CLK_EN: SD clock on
        or32(base + CONTROL0, (0x7 << 9) | (1 << 8));        // bus power 3.3V
        Magic.store32(base + IRPT_EN, 0xFFFFFFFF);
        Magic.store32(base + IRPT_MASK, 0xFFFFFFFF);
        Magic.store32(base + INTERRUPT, 0xFFFFFFFF);

        if (!command(0 << 24 | RSP_NONE, 0))                 // CMD0 GO_IDLE (harmless reset to a known state)
        {
            return -4;
        }
        VM.delayMs(2);
        // CMD5 IO_SEND_OP_COND: arg 0 probes; then request the voltage window until the card powers up.
        if (!command(5 << 24 | RSP_48, 0))                   // R4, no CRC/index check
        {
            return -5;
        }
        int ocr = Magic.load32(base + RESP0) & 0x00FFFFFF;   // voltage window bits
        int tries = 0;
        while (true)
        {
            if (!command(5 << 24 | RSP_48, ocr))             // set the window
            {
                return -6;
            }
            if ((Magic.load32(base + RESP0) & 0x80000000) != 0)   // bit31: IO ready
            {
                break;
            }
            tries = tries + 1;
            if (tries > 1000)
            {
                return -7;
            }
            VM.delayMs(1);
        }
        if (!command(3 << 24 | RSP_48 | CRC_CHK | IX_CHK, 0))     // CMD3 SEND_RELATIVE_ADDR
        {
            return -8;
        }
        rca = Magic.load32(base + RESP0) & 0xFFFF0000L;          // top 16 bits are the RCA
        if (!command(7 << 24 | RSP_48B | CRC_CHK | IX_CHK, rca)) // CMD7 SELECT_CARD
        {
            return -9;
        }
        // 4-bit bus width: CCCR Bus Interface Control (F0 reg 0x07) low 2 bits = 0b10, then host HCTL_DWIDTH.
        int bic = cmd52Read(0, 0x07);
        cmd52Write(0, 0x07, (bic & ~0x3) | 0x2);
        or32(base + CONTROL0, 1 << 1);                          // HCTL_DWIDTH (4-bit)
        return 0;
    }

    /** CONTROL1 (clock/reset control) — for diagnostics after {@link #setClock}. */
    public static int control1()
    {
        return Magic.load32(base + CONTROL1);
    }

    /** Raise the SDIO clock to {@code hz} (call after enumeration; e.g. 25-50 MHz once F2 is stable). */
    public static void setClock(int hz)
    {
        and32(base + CONTROL1, ~(1 << 2));                      // CLK_EN off while changing
        int c1 = (Magic.load32(base + CONTROL1) & ~0xFFC0) | clockDiv(hz);
        Magic.store32(base + CONTROL1, c1);
        waitSet(base + CONTROL1, 1 << 1);                       // CLK_STABLE
        or32(base + CONTROL1, 1 << 2);                          // CLK_EN on
    }

    // ----- CMD52 IO_RW_DIRECT (single-byte control register access) --------------------------------------
    // arg: [R/W:1][func:3][RAW:1][0][reg addr:17][0][data:8]; R5 response low 8 bits = data read/echoed.

    /** Read one byte from {@code func} register {@code addr}; -1 on error. */
    public static int cmd52Read(int func, int addr)
    {
        long arg = ((long) (func & 7) << 28) | ((long) (addr & 0x1FFFF) << 9);
        if (!command(52 << 24 | RSP_48 | CRC_CHK | IX_CHK, arg))
        {
            return -1;
        }
        return Magic.load32(base + RESP0) & 0xFF;
    }

    /** Write one byte {@code data} to {@code func} register {@code addr}; false on error. */
    public static boolean cmd52Write(int func, int addr, int data)
    {
        long arg = 0x80000000L | ((long) (func & 7) << 28) | ((long) (addr & 0x1FFFF) << 9) | (data & 0xFF);
        return command(52 << 24 | RSP_48 | CRC_CHK | IX_CHK, arg);
    }

    // ----- CMD53 IO_RW_EXTENDED (bulk transfer, PIO) -----------------------------------------------------
    // arg: [R/W:1][func:3][block mode:1][incr addr:1][reg addr:17][count:9].

    /** Read {@code blocks}×{@code blkSize} bytes from {@code func}/{@code addr} into heap buffer {@code dst}. */
    public static boolean cmd53Read(int func, int addr, boolean incr, long dst, int blocks, int blkSize)
    {
        if (!prepData(blocks, blkSize))
        {
            return false;
        }
        long arg = cmd53Arg(func, addr, incr, blocks, blkSize, false);
        int tm = 53 << 24 | RSP_48 | CRC_CHK | IX_CHK | IS_DATA | DAT_READ;
        if (blocks > 1) { tm = tm | TM_BLKCNT_EN | TM_MULTI_BLOCK; }
        if (!command(tm, arg))
        {
            return false;
        }
        return pioRead(dst, blocks, blkSize);
    }

    /** Write {@code blocks}×{@code blkSize} bytes from heap buffer {@code src} to {@code func}/{@code addr}. */
    public static boolean cmd53Write(int func, int addr, boolean incr, long src, int blocks, int blkSize)
    {
        if (!prepData(blocks, blkSize))
        {
            return false;
        }
        long arg = cmd53Arg(func, addr, incr, blocks, blkSize, true);
        int tm = 53 << 24 | RSP_48 | CRC_CHK | IX_CHK | IS_DATA;
        if (blocks > 1) { tm = tm | TM_BLKCNT_EN | TM_MULTI_BLOCK; }
        if (!command(tm, arg))
        {
            return false;
        }
        return pioWrite(src, blocks, blkSize);
    }

    /** Build a CMD53 argument. In byte mode ({@code blocks==1} via a non-block caller) count = byte count;
     *  in block mode count = block count. We use block mode whenever {@code blocks>1}, byte mode otherwise. */
    private static long cmd53Arg(int func, int addr, boolean incr, int blocks, int blkSize, boolean write)
    {
        boolean blockMode = blocks > 1;
        int count = blockMode ? blocks : blkSize;               // byte mode: count is the byte length
        long arg = ((long) (func & 7) << 28) | ((long) (addr & 0x1FFFF) << 9) | (count & 0x1FF);
        if (blockMode) { arg = arg | (1L << 27); }
        if (incr)      { arg = arg | (1L << 26); }
        if (write)     { arg = arg | 0x80000000L; }
        return arg;
    }

    private static boolean prepData(int blocks, int blkSize)
    {
        if (!waitClear(base + STATUS, SR_DAT_INHIBIT))
        {
            return false;
        }
        int n = blocks > 1 ? blocks : 1;
        Magic.store32(base + BLKSIZECNT, (n << 16) | (blkSize & 0xFFF));
        return true;
    }

    private static boolean pioRead(long dst, int blocks, int blkSize)
    {
        int total = (blocks > 1 ? blocks : 1) * ((blkSize + 3) / 4);   // words
        int i = 0;
        while (i < total)
        {
            if ((i % (blkSize / 4)) == 0 && !waitSet(base + INTERRUPT, INT_READ_RDY | INT_ERR))
            {
                return false;
            }
            Magic.store32(dst + i * 4L, Magic.load32(base + DATA));
            i = i + 1;
        }
        boolean ok = waitSet(base + INTERRUPT, INT_DATA_DONE);
        Magic.store32(base + INTERRUPT, INT_READ_RDY | INT_DATA_DONE);
        return ok;
    }

    private static boolean pioWrite(long src, int blocks, int blkSize)
    {
        int total = (blocks > 1 ? blocks : 1) * ((blkSize + 3) / 4);
        int i = 0;
        while (i < total)
        {
            if ((i % (blkSize / 4)) == 0 && !waitSet(base + INTERRUPT, INT_WRITE_RDY | INT_ERR))
            {
                return false;
            }
            Magic.store32(base + DATA, Magic.load32(src + i * 4L));
            i = i + 1;
        }
        boolean ok = waitSet(base + INTERRUPT, INT_DATA_DONE);
        Magic.store32(base + INTERRUPT, INT_WRITE_RDY | INT_DATA_DONE);
        return ok;
    }

    /** Issue a command with argument {@code arg}; wait for completion. False on error/timeout. */
    private static boolean command(int cmdtm, long arg)
    {
        if (!waitClear(base + STATUS, SR_CMD_INHIBIT))
        {
            return false;
        }
        Magic.store32(base + INTERRUPT, 0xFFFFFFFF);
        Magic.store32(base + ARG1, (int) arg);
        Magic.store32(base + CMDTM, cmdtm);
        if (!waitSet(base + INTERRUPT, INT_CMD_DONE | INT_ERR))
        {
            return false;
        }
        int flags = Magic.load32(base + INTERRUPT);
        Magic.store32(base + INTERRUPT, INT_CMD_DONE);
        return (flags & INT_ERR) == 0;
    }

    /** SDHCI v3 divider for {@code targetHz} from the mailbox-reported EMMC base clock (SD clk = base/2div). */
    private static int clockDiv(int targetHz)
    {
        int div = 1;
        while (baseClockHz / (2 * div) > targetHz && div < 1023)
        {
            div = div + 1;
        }
        return ((div & 0xFF) << 8) | ((div >> 8 & 0x3) << 6);
    }

    private static void or32(long reg, int bits)
    {
        Magic.store32(reg, Magic.load32(reg) | bits);
    }

    private static void and32(long reg, int bits)
    {
        Magic.store32(reg, Magic.load32(reg) & bits);
    }

    private static boolean waitSet(long reg, int mask)
    {
        int n = 0;
        while ((Magic.load32(reg) & mask) == 0)
        {
            n = n + 1;
            if (n > 5000000)
            {
                return false;
            }
        }
        return true;
    }

    private static boolean waitClear(long reg, int mask)
    {
        int n = 0;
        while ((Magic.load32(reg) & mask) != 0)
        {
            n = n + 1;
            if (n > 5000000)
            {
                return false;
            }
        }
        return true;
    }
}
