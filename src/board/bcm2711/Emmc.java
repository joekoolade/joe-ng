package board.bcm2711;

import magic.Magic;

/**
 * A minimal EMMC2 (Arasan SDHCI) SD-card block driver — the storage half of the
 * self-hosting fixpoint (PLAN.md §M5.5d): once the metal writer has reproduced
 * its own {@code kernel8.img} in a heap buffer, this driver is how it writes it
 * back to the card the firmware booted from. This slice brings the controller up
 * and reads a single 512-byte block by polled PIO (no interrupts, no DMA).
 *
 * <p>Written as ordinary Java compiled to A64 by our own baseline compiler, like
 * every other driver here — MMIO is plain {@link Magic#load32}/{@link Magic#store32}.
 */
public final class Emmc
{
    private Emmc() {}

    // Register offsets from the controller base (SDHCI-standard).
    private static final long ARG2 = 0x00, BLKSIZECNT = 0x04, ARG1 = 0x08, CMDTM = 0x0C;
    private static final long RESP0 = 0x10, DATA = 0x20, STATUS = 0x24;
    private static final long CONTROL0 = 0x28, CONTROL1 = 0x2C, INTERRUPT = 0x30;
    private static final long IRPT_MASK = 0x34, IRPT_EN = 0x38, SLOTISR_VER = 0xFC;

    // The Pi 4 wires the SD slot to EMMC2 (0xFE340000); QEMU's raspi4b puts the card on the
    // legacy EMMC (0xFE300000). Pick whichever reports a card present (STATUS bit16).
    private static final long EMMC2_BASE = 0xFE34_0000L;
    private static final long EMMC_BASE  = 0xFE30_0000L;
    private static long base;

    /** Controller base with a card inserted, or 0 if none found. */
    private static long detectBase()
    {
        if ((Magic.load32(EMMC2_BASE + STATUS) & (1 << 16)) != 0)
        {
            return EMMC2_BASE;
        }
        if ((Magic.load32(EMMC_BASE + STATUS) & (1 << 16)) != 0)
        {
            return EMMC_BASE;
        }
        return 0L;
    }

    // CMDTM command encodings: (index<<24) | (rspType<<16) | flags.
    private static final int RSP_NONE = 0;
    private static final int RSP_136  = 1 << 16;   // R2 (CID/CSD)
    private static final int RSP_48   = 2 << 16;   // R1/R3/R6/R7
    private static final int RSP_48B  = 3 << 16;   // R1b (busy)
    private static final int CRC_CHK  = 1 << 19;
    private static final int IX_CHK   = 1 << 20;
    private static final int IS_DATA  = 1 << 21;
    private static final int DAT_READ = 1 << 4;    // TM_DAT_DIR: 1 = card -> host

    // INTERRUPT (present-state / status) bits we poll.
    private static final int INT_CMD_DONE  = 1;        // command complete
    private static final int INT_DATA_DONE = 1 << 1;   // transfer complete
    private static final int INT_READ_RDY  = 1 << 5;   // read FIFO has data
    private static final int INT_ERR       = 1 << 15;  // any error (bits 16+ folded up)

    // STATUS (present state) bits.
    private static final int SR_CMD_INHIBIT = 1;        // CMD line busy
    private static final int SR_DAT_INHIBIT = 1 << 1;   // DAT line busy

    private static long rca;      // the card's relative address (from CMD3), shifted for CMD7/select
    private static boolean sdhc;  // true if a high-capacity card (block-addressed)

    /**
     * Bring the controller and card up: reset, identification clock, then the
     * CMD0/CMD8/ACMD41/CMD2/CMD3/CMD7/CMD16 init handshake. Returns 0 on success,
     * else a negative step code identifying where it stalled.
     */
    public static int init()
    {
        base = detectBase();
        if (base == 0L)
        {
            return -1;                                      // no card in either controller
        }
        // Full software reset of the host controller (CONTROL1 SRST_HC = bit24).
        Magic.store32(base + CONTROL0, 0);
        or32(base + CONTROL1, 1 << 24);
        if (!waitClear(base + CONTROL1, 1 << 24))
        {
            return -2;
        }
        // Identification clock (~400 kHz) + max data timeout, then enable it.
        int c1 = (1 << 0)                    // CLK_INTLEN (internal clock enable)
               | clockDiv(400000)            // frequency select
               | (11 << 16);                 // DATA_TOUNIT (timeout ~ TMCLK * 2^24)
        Magic.store32(base + CONTROL1, c1);
        if (!waitSet(base + CONTROL1, 1 << 1))             // CLK_STABLE
        {
            return -3;
        }
        or32(base + CONTROL1, 1 << 2);                     // CLK_EN (SD clock on)
        // SD bus power on at 3.3V (CONTROL0 [11:9] voltage, [8] power). The Pi firmware normally
        // owns power, but a generic SDHCI (QEMU) gates command response on it.
        or32(base + CONTROL0, (0x7 << 9) | (1 << 8));
        // Route all events to the INTERRUPT status register; we poll it.
        Magic.store32(base + IRPT_EN, 0xFFFFFFFF);
        Magic.store32(base + IRPT_MASK, 0xFFFFFFFF);
        Magic.store32(base + INTERRUPT, 0xFFFFFFFF);       // clear any stale flags

        if (!command(0 << 24 | RSP_NONE, 0))               // CMD0 GO_IDLE_STATE
        {
            return -4;
        }
        if (!command(8 << 24 | RSP_48 | CRC_CHK | IX_CHK, 0x1AA))   // CMD8 SEND_IF_COND
        {
            return -5;
        }
        if ((Magic.load32(base + RESP0) & 0xFFF) != 0x1AA)         // echoes VHS + check pattern
        {
            return -6;
        }
        // ACMD41 (CMD55 then ACMD41) until the card leaves busy; HCS asks for SDHC.
        int tries = 0;
        long resp;
        while (true)
        {
            if (!command(55 << 24 | RSP_48 | CRC_CHK | IX_CHK, 0))  // CMD55 APP_CMD (rca 0)
            {
                return -7;
            }
            if (!command(41 << 24 | RSP_48, 0x51FF8000))            // ACMD41 (HCS | voltage window)
            {
                return -8;
            }
            resp = Magic.load32(base + RESP0) & 0xFFFFFFFFL;
            if ((resp & 0x80000000L) != 0)                          // bit31: power-up done
            {
                break;
            }
            tries += 1;
            if (tries > 1000000)
            {
                return -9;
            }
        }
        sdhc = (resp & 0x40000000L) != 0;                          // CCS: high-capacity (block-addressed)

        if (!command(2 << 24 | RSP_136 | CRC_CHK, 0))              // CMD2 ALL_SEND_CID
        {
            return -10;
        }
        if (!command(3 << 24 | RSP_48 | CRC_CHK | IX_CHK, 0))      // CMD3 SEND_RELATIVE_ADDR
        {
            return -11;
        }
        rca = Magic.load32(base + RESP0) & 0xFFFF0000L;            // top 16 bits are the RCA
        if (!command(7 << 24 | RSP_48B | CRC_CHK | IX_CHK, rca))   // CMD7 SELECT_CARD
        {
            return -12;
        }
        if (!command(16 << 24 | RSP_48 | CRC_CHK | IX_CHK, 512))   // CMD16 SET_BLOCKLEN = 512
        {
            return -13;
        }
        return 0;
    }

    /**
     * Read the 512-byte block at {@code lba} into the heap buffer at {@code dst}
     * (128 words). Returns true on success. {@link #init} must have run.
     */
    public static boolean readBlock(long lba, long dst)
    {
        if (!waitClear(base + STATUS, SR_DAT_INHIBIT))
        {
            return false;
        }
        Magic.store32(base + BLKSIZECNT, (1 << 16) | 512);   // one block of 512 bytes
        long arg = sdhc ? lba : lba * 512L;                        // SDHC = block index; SDSC = byte offset
        if (!command(17 << 24 | RSP_48 | CRC_CHK | IX_CHK | IS_DATA | DAT_READ, arg))   // CMD17
        {
            return false;
        }
        if (!waitSet(base + INTERRUPT, INT_READ_RDY))        // FIFO ready
        {
            return false;
        }
        int i = 0;
        while (i < 128)                                            // 128 words = 512 bytes, PIO
        {
            Magic.store32(dst + i * 4L, Magic.load32(base + DATA));
            i += 1;
        }
        Magic.store32(base + INTERRUPT, INT_READ_RDY | INT_DATA_DONE);   // ack
        return true;
    }

    /** Issue a command with argument {@code arg}; wait for completion. False on error/timeout. */
    private static boolean command(int cmdtm, long arg)
    {
        if (!waitClear(base + STATUS, SR_CMD_INHIBIT))       // CMD line free
        {
            return false;
        }
        Magic.store32(base + INTERRUPT, 0xFFFFFFFF);         // clear prior flags
        Magic.store32(base + ARG1, (int) arg);
        Magic.store32(base + CMDTM, cmdtm);
        if (!waitSet(base + INTERRUPT, INT_CMD_DONE | INT_ERR))
        {
            return false;
        }
        int flags = Magic.load32(base + INTERRUPT);
        Magic.store32(base + INTERRUPT, INT_CMD_DONE);       // ack command-done
        return (flags & INT_ERR) == 0;
    }

    /**
     * SDHCI v3 clock divider for {@code targetHz} from the EMMC base clock. The
     * SD clock is base/(2*div); CONTROL1 packs div low-8 at [15:8] and high-2 at
     * [7:6]. The base clock is unknown here, so assume ~100 MHz (Pi 4 default) —
     * exactness only matters for signal integrity, and the card tolerates a
     * conservative identification clock.
     */
    private static int clockDiv(int targetHz)
    {
        int base = 100000000;
        int div = 1;
        while (base / (2 * div) > targetHz && div < 1023)
        {
            div += 1;
        }
        return ((div & 0xFF) << 8) | ((div >> 8 & 0x3) << 6);
    }

    private static void or32(long reg, int bits)
    {
        Magic.store32(reg, Magic.load32(reg) | bits);
    }

    /** Spin until every bit in {@code mask} is set at {@code reg} (bounded); false on timeout. */
    private static boolean waitSet(long reg, int mask)
    {
        int n = 0;
        while ((Magic.load32(reg) & mask) == 0)
        {
            n += 1;
            if (n > 5000000)
            {
                return false;
            }
        }
        return true;
    }

    /** Spin until every bit in {@code mask} is clear at {@code reg} (bounded); false on timeout. */
    private static boolean waitClear(long reg, int mask)
    {
        int n = 0;
        while ((Magic.load32(reg) & mask) != 0)
        {
            n += 1;
            if (n > 5000000)
            {
                return false;
            }
        }
        return true;
    }
}
