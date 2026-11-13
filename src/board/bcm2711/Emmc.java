/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-23
 */
package board.bcm2711;

import magic.Magic;

/**
 * The microSD card's block driver over EMMC2 (SDHCI), polled PIO (no interrupts, no DMA). It began as the
 * storage half of the retired self-hosting fixpoint (PLAN.md §M5.5d); it is now the block device under the
 * guest's {@code /boot} mount ({@code jdk.internal.misc.MetalBlock}). {@link #init} identifies the card at
 * 400 kHz, then switches to a 4-bit bus at 25 MHz; {@link #readBlocks}/{@link #writeBlocks} move runs of
 * 512-byte blocks with CMD18/CMD25 (auto-CMD12).
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

    /**
     * Controller base with a card inserted, or 0 if none found. ON HARDWARE IT IS ALWAYS EMMC2: the legacy
     * controller at 0xFE300000 is the WiFi chip's SDIO bus ({@code Sdio.base}), and {@link #init} begins with a
     * full controller reset -- falling back to it would cut the WiFi off mid-run. The firmware booted from the
     * microSD card, so EMMC2 has one even if its card-detect bit does not say so. QEMU (no measured core
     * clock) puts the card on the legacy controller, so only there is the fallback allowed.
     */
    private static long detectBase()
    {
        if (Uart.coreHz > 10_000_000)
        {
            return EMMC2_BASE;
        }
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
    private static final int BLKCNT_EN = 1 << 1;   // TM_BLKCNT_EN: BLKSIZECNT's count bounds the transfer
    private static final int AUTO_CMD12 = 1 << 2;  // TM_AUTO_CMD_EN = 01: controller sends CMD12 at the end
    private static final int MULTI_BLK = 1 << 5;   // TM_MULTI_BLOCK

    /** Largest run one CMD18/CMD25 moves; longer requests are split (BLKSIZECNT's count is 16 bits). */
    private static final int MAX_RUN = 1024;

    // INTERRUPT (present-state / status) bits we poll.
    private static final int INT_CMD_DONE  = 1;        // command complete
    private static final int INT_DATA_DONE = 1 << 1;   // transfer complete
    private static final int INT_WRITE_RDY = 1 << 4;   // write FIFO ready for data
    private static final int INT_READ_RDY  = 1 << 5;   // read FIFO has data
    private static final int INT_ERR       = 1 << 15;  // any error (bits 16+ folded up)

    // STATUS (present state) bits.
    private static final int SR_CMD_INHIBIT = 1;        // CMD line busy
    private static final int SR_DAT_INHIBIT = 1 << 1;   // DAT line busy

    private static long rca;      // the card's relative address (from CMD3), shifted for CMD7/select
    private static boolean sdhc;  // true if a high-capacity card (block-addressed)
    private static int baseHz;    // the controller's base clock (measured), for the SD clock divider
    /** The SD clock {@link #init} settled on, in Hz (0 = still at the identification clock). */
    public static int busHz;
    /** Data-transfer errors seen (each one also resets the DAT line). */
    public static int dataErrors;

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
        busHz = 0;
        baseHz = 0;
        if (Uart.coreHz > 10_000_000)
        {
            baseHz = Mailbox.measuredClockHz(base == EMMC2_BASE ? Bcm2711.CLOCK_ID_EMMC2 : Bcm2711.CLOCK_ID_EMMC);
        }
        if (baseHz < 1_000_000)
        {
            baseHz = 100_000_000;                           // QEMU / no answer: the old assumption
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
        // Data transfer mode: a 4-bit bus (ACMD6, then the host's CONTROL0 bit1) at 25 MHz, the SD default
        // speed every card supports. The identification clock (400 kHz, 1 bit) moves ~50 KB/s -- a 39 MB
        // kernel would take a quarter of an hour.
        if (!command(55 << 24 | RSP_48 | CRC_CHK | IX_CHK, rca))   // CMD55 APP_CMD (selected card)
        {
            return -14;
        }
        if (!command(6 << 24 | RSP_48 | CRC_CHK | IX_CHK, 2))      // ACMD6 SET_BUS_WIDTH = 4 bits
        {
            return -15;
        }
        or32(base + CONTROL0, 1 << 1);                             // HCTL_DWIDTH: host on 4 bits too
        if (!setClock(25_000_000))
        {
            return -16;
        }
        return 0;
    }

    /** Switch the SD clock to (at most) {@code hz}: stop it, load the divider, wait stable, start it. */
    private static boolean setClock(int hz)
    {
        int c1 = Magic.load32(base + CONTROL1);
        Magic.store32(base + CONTROL1, c1 & ~(1 << 2));            // CLK_EN off while it changes
        c1 = (c1 & ~((0xFF << 8) | (0x3 << 6)) & ~(1 << 2)) | clockDiv(hz);
        Magic.store32(base + CONTROL1, c1);
        if (!waitSet(base + CONTROL1, 1 << 1))                     // CLK_STABLE
        {
            return false;
        }
        or32(base + CONTROL1, 1 << 2);                             // CLK_EN
        busHz = baseHz / (2 * divOf(hz));
        return true;
    }

    /**
     * Read {@code n} consecutive 512-byte blocks from {@code lba} into memory at {@code dst} (word aligned).
     * CMD18 with auto-CMD12, split into runs of at most {@link #MAX_RUN}. False on any error.
     */
    public static boolean readBlocks(long lba, long dst, int n)
    {
        while (n > 0)
        {
            int run = n < MAX_RUN ? n : MAX_RUN;
            if (!transfer(lba, dst, run, false))
            {
                return false;
            }
            lba += run;
            dst += run * 512L;
            n -= run;
        }
        return true;
    }

    /** Write {@code n} consecutive 512-byte blocks to {@code lba} from memory at {@code src}. CMD25. */
    public static boolean writeBlocks(long lba, long src, int n)
    {
        while (n > 0)
        {
            int run = n < MAX_RUN ? n : MAX_RUN;
            if (!transfer(lba, src, run, true))
            {
                return false;
            }
            lba += run;
            src += run * 512L;
            n -= run;
        }
        return true;
    }

    /**
     * One multi-block transfer. Each block waits for its own ready flag and then CLEARS it: the flag is a
     * latched status, so without the clear every wait after the first passes at once and the PIO loop runs
     * ahead of the FIFO (the SDIO write bug of #506, on the other controller).
     */
    private static boolean transfer(long lba, long mem, int n, boolean write)
    {
        if (!waitClear(base + STATUS, SR_DAT_INHIBIT))
        {
            return dataFail();
        }
        Magic.store32(base + BLKSIZECNT, (n << 16) | 512);
        long arg = sdhc ? lba : lba * 512L;
        int cmd = write ? (25 << 24) : (18 << 24 | DAT_READ);
        if (!command(cmd | RSP_48 | CRC_CHK | IX_CHK | IS_DATA | MULTI_BLK | BLKCNT_EN | AUTO_CMD12, arg))
        {
            return dataFail();
        }
        int ready = write ? INT_WRITE_RDY : INT_READ_RDY;
        int b = 0;
        while (b < n)
        {
            if (!waitData(ready))
            {
                return dataFail();
            }
            Magic.store32(base + INTERRUPT, ready);                // re-arm for the next block
            long p = mem + b * 512L;
            int i = 0;
            while (i < 128)
            {
                if (write)
                {
                    Magic.store32(base + DATA, Magic.load32(p + i * 4L));
                }
                else
                {
                    Magic.store32(p + i * 4L, Magic.load32(base + DATA));
                }
                i += 1;
            }
            b += 1;
        }
        if (!waitData(INT_DATA_DONE))                              // last block programmed + CMD12 sent
        {
            return dataFail();
        }
        Magic.store32(base + INTERRUPT, INT_DATA_DONE | INT_WRITE_RDY | INT_READ_RDY);
        return true;
    }

    /** Wait for {@code mask} or an error flag; false on error or timeout. */
    private static boolean waitData(int mask)
    {
        if (!waitSet(base + INTERRUPT, mask | INT_ERR))
        {
            return false;
        }
        return (Magic.load32(base + INTERRUPT) & INT_ERR) == 0;
    }

    /** A data transfer failed: count it, reset the CMD and DAT lines (SRST_CMD bit25, SRST_DATA bit26). */
    private static boolean dataFail()
    {
        dataErrors += 1;
        or32(base + CONTROL1, (1 << 25) | (1 << 26));
        waitClear(base + CONTROL1, (1 << 25) | (1 << 26));
        Magic.store32(base + INTERRUPT, 0xFFFFFFFF);
        return false;
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

    /**
     * Write the 512-byte block at {@code lba} from the heap buffer at {@code src}
     * (128 words). Returns true on success. {@link #init} must have run.
     */
    public static boolean writeBlock(long lba, long src)
    {
        if (!waitClear(base + STATUS, SR_DAT_INHIBIT))
        {
            return false;
        }
        Magic.store32(base + BLKSIZECNT, (1 << 16) | 512);         // one block of 512 bytes
        long arg = sdhc ? lba : lba * 512L;
        if (!command(24 << 24 | RSP_48 | CRC_CHK | IX_CHK | IS_DATA, arg))   // CMD24 (DAT_DIR=write)
        {
            return false;
        }
        if (!waitSet(base + INTERRUPT, INT_WRITE_RDY))             // FIFO ready to accept data
        {
            return false;
        }
        int i = 0;
        while (i < 128)                                            // 128 words = 512 bytes, PIO
        {
            Magic.store32(base + DATA, Magic.load32(src + i * 4L));
            i += 1;
        }
        if (!waitSet(base + INTERRUPT, INT_DATA_DONE))             // transfer complete (card programmed)
        {
            return false;
        }
        Magic.store32(base + INTERRUPT, INT_WRITE_RDY | INT_DATA_DONE);   // ack
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
     * SDHCI v3 clock divider for {@code targetHz} from the controller's base clock
     * ({@link #baseHz}: measured over the mailbox on hardware, 100 MHz assumed on
     * QEMU). The SD clock is base/(2*div); CONTROL1 packs div low-8 at [15:8] and
     * high-2 at [7:6].
     */
    private static int clockDiv(int targetHz)
    {
        int div = divOf(targetHz);
        return ((div & 0xFF) << 8) | ((div >> 8 & 0x3) << 6);
    }

    /** The smallest N >= 1 with base/(2N) <= {@code targetHz}. */
    private static int divOf(int targetHz)
    {
        int div = 1;
        while (baseHz / (2 * div) > targetHz && div < 1023)
        {
            div += 1;
        }
        return div;
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
