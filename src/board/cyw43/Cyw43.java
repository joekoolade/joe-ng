/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-04
 */
package board.cyw43;

import board.bcm2711.Sdio;
import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VM;

/**
 * Cypress CYW43455 WiFi chip driver over SDIO (PLAN.md WiFi §M1). This slice (M1a) is the first flashable
 * checkpoint: enumerate the chip as an SDIO device (via {@link Sdio}), dump its CCCR, enable the backplane
 * function (F1), request the ALP clock, then read the chip's identity through the backplane window — proving
 * the whole SDIO link (pins, host controller, CMD5/52/53, backplane addressing) end to end before the much
 * larger firmware-upload step (M1b).
 *
 * <p>Real-hardware only: QEMU does not emulate the CYW43455 (the run is gated on {@code Uart.coreHz} in
 * {@code VM.run}). Every step logs a short marker + register value over the UART, and every wait is bounded,
 * so one flash+paste tells us exactly how far it got.
 *
 * <p>Source: SDIO Simplified Spec (CCCR/FBR, CMD52/53); Broadcom/Cypress SDIO backplane windowing (the
 * SBSDIO_FUNC1_SBADDR{LOW,MID,HIGH} + SB_ACCESS_2_4B window scheme) and CHIPCLKCSR ALP/HT clock bits — the
 * same concepts brcmfmac / the Infineon cyw43-driver / Circle implement; written from scratch. See SOURCES.md.
 */
public final class Cyw43
{
    private Cyw43() {}

    // SDIO function numbers.
    private static final int F0 = 0;            // CCCR / FBR (standard SDIO)
    private static final int F1 = 1;            // chip backplane (registers + RAM, windowed)
    private static final int F2 = 2;            // WLAN data (SDPCM packets) — enabled once firmware boots

    // CCCR (F0) register offsets.
    private static final int CCCR_IOEx = 0x02;  // enable I/O functions (bit n)
    private static final int CCCR_IORx = 0x03;  // I/O function n ready (bit n)
    private static final int CCCR_IEN  = 0x04;  // interrupt enable (bit0 master, bit1 F1, bit2 F2)
    private static final int FBR2_BLKSIZE = 0x210;   // F2's FBR: I/O block size, low byte (high byte at +1)
    static final int F2_BLOCK = 512;                 // F2 block size (brcmfmac's SDIO_FUNC2_BLOCKSIZE)
    private static final int SDHCI_CARD_INT = 1 << 8;   // SDHCI INTERRUPT: SDIO device asserted its IRQ
    private static final int CCCR_BUS  = 0x07;  // bus interface control (bus width — set by Sdio.init)
    private static final int CCCR_CAP  = 0x08;  // card capability
    private static final int CCCR_REV  = 0x00;  // CCCR/SDIO revision

    // F1 backplane window registers (SBSDIO_FUNC1_*).
    private static final int SBADDR_LOW  = 0x1000A;   // backplane address bits [15:8]
    private static final int SBADDR_MID  = 0x1000B;   // [23:16]
    private static final int SBADDR_HIGH = 0x1000C;   // [31:24]
    private static final int CHIPCLKCSR  = 0x1000E;   // ALP/HT clock request+status
    private static final int SB_WIN_MASK = 0x07FFF;   // low 15 bits addressed within the window
    private static final int SB_ACCESS_4B = 0x08000;  // OR into the F1 offset for a 32-bit access

    // CHIPCLKCSR bits.
    private static final int ALP_AVAIL_REQ = 0x08;
    private static final int HT_AVAIL_REQ  = 0x10;
    private static final int ALP_AVAIL     = 0x40;
    private static final int HT_AVAIL      = 0x80;

    // Chip backplane: the ChipCommon core sits at the silicon enumeration base; its first word is the ID.
    private static final long SI_ENUM_BASE = 0x18000000L;

    private static Address buf;         // a small heap buffer for CMD53 word transfers
    private static long curWindow = -1; // last backplane window set (avoid redundant CMD52s)

    /** M1a: enumerate + backplane chip-ID probe. Logs each step; returns the chip ID (low 16 bits), or 0. */
    public static int probeChip()
    {
        buf = Heap.allocData(64);

        int rc = Sdio.init();                            // pins + host + CMD5/3/7 enumerate + 4-bit
        log(Magic.bytes("sdio init rc="), rc);
        if (rc != 0)
        {
            return 0;
        }

        // CCCR dump — proves CMD52 (IO_RW_DIRECT) round-trips on F0.
        log(Magic.bytes("cccr rev="), Sdio.cmd52Read(F0, CCCR_REV));
        log(Magic.bytes("cccr cap="), Sdio.cmd52Read(F0, CCCR_CAP));
        log(Magic.bytes("cccr ioe="), Sdio.cmd52Read(F0, CCCR_IOEx));
        log(Magic.bytes("cccr ior="), Sdio.cmd52Read(F0, CCCR_IORx));

        // Enable the backplane function (F1) and wait for it to report ready.
        Sdio.cmd52Write(F0, CCCR_IOEx, 1 << F1);
        int tries = 0;
        while ((Sdio.cmd52Read(F0, CCCR_IORx) & (1 << F1)) == 0)
        {
            tries = tries + 1;
            if (tries > 500)
            {
                log(Magic.bytes("F1 not ready ior="), Sdio.cmd52Read(F0, CCCR_IORx));
                return 0;
            }
            VM.delayMs(1);
        }
        log(Magic.bytes("F1 ready ior="), Sdio.cmd52Read(F0, CCCR_IORx));

        // Request the ALP clock (backplane reads need a clock running) and wait for it.
        Sdio.cmd52Write(F1, CHIPCLKCSR, ALP_AVAIL_REQ);
        tries = 0;
        while ((Sdio.cmd52Read(F1, CHIPCLKCSR) & ALP_AVAIL) == 0)
        {
            tries = tries + 1;
            if (tries > 500)
            {
                log(Magic.bytes("no ALP clock csr="), Sdio.cmd52Read(F1, CHIPCLKCSR));
                return 0;
            }
            VM.delayMs(1);
        }
        log(Magic.bytes("ALP clock csr="), Sdio.cmd52Read(F1, CHIPCLKCSR));

        // Read the ChipCommon ID word through the backplane window.
        int idword = bpRead32(SI_ENUM_BASE);
        log(Magic.bytes("chipcommon idword="), idword);
        int chipId = idword & 0xFFFF;
        int chipRev = (idword >> 16) & 0xF;
        board.bcm2711.Uart.write(Magic.bytes("wifi: chip id "));
        VM.printHex((long) chipId);
        board.bcm2711.Uart.write(Magic.bytes(" rev "));
        VM.printDec(chipRev);
        board.bcm2711.Uart.putc(0x0A);

        // dumpErom();  (M1b-1 done — core map decoded: ARM CR4 wrapper 0x18102000, RAM at 0x0/0x180000/0x200000)
        // ramTest();   (M1b-2a done — TCM writable at rambase 0x198000 once the CR4 is out of reset + CPUHALT)
        armCr4Prepare(ARMCR4_WRAP);                      // core out of reset, CPU halted -> TCM accessible
        int rstvec = uploadFirmware();                   // M1b-2b-i: stream the .bin into RAM; returns fw[0]
        if (rstvec == 0)
        {
            return chipId;
        }
        int ramsize = computeRamsize();                  // TCM size (0xC8000) for NVRAM placement
        if (!uploadNvram(ramsize))                       // M1b-2b-ii: NVRAM at rambase+ramsize-varsz + token
        {
            return chipId;
        }
        bootFirmware(ramsize, rstvec);                   // reset vector -> 0, release the ARM, wait F2 ready
        return chipId;
    }

    /** Read the ARM CR4 TCM size by summing its banks (as brcmf_chip_get_raminfo does), logging each bank
     *  and the total — needed to place the NVRAM at rambase+ramsize-varsz. Benign (reads + bank-index writes). */
    static int computeRamsize()
    {
        int cap = bpRead32(ARMCR4_CORE + 0x00);
        int totb = (cap & 0xF) + ((cap >> 4) & 0xF);     // TCBANB + TCBBNB
        log(Magic.bytes("tcm banks="), totb);
        int ramsize = 0;
        int i = 0;
        while (i < totb)
        {
            bpWrite32(ARMCR4_CORE + 0x40, i);            // ARMCR4_BANKIDX
            int bx = bpRead32(ARMCR4_CORE + 0x44);       // ARMCR4_BANKINFO
            int banksz = ((bx & 0x7F) + 1) * 8192;       // (BSZ+1) * 8K
            log(Magic.bytes("bank info="), bx);
            ramsize = ramsize + banksz;
            i = i + 1;
        }
        log(Magic.bytes("tcm ramsize="), ramsize);
        board.bcm2711.Uart.write(Magic.bytes("  fw end="));
        VM.printHex(RAMBASE + 0x94C1DL);                 // where the 609 KB firmware ends (for NVRAM-fit check)
        board.bcm2711.Uart.putc(0x0A);
        return ramsize;
    }

    private static final long RAMBASE = 0x198000L;       // brcmfmac 4345 firmware load address (confirmed writable)

    /**
     * M1b-2b-i: stream {@code brcmfmac43455-sdio.bin} from the RAMFS into chip RAM at {@link #RAMBASE} via
     * block CMD53 writes, then read back the first and last words to verify. (NVRAM + reset-vector + ARM
     * release + F2-ready come in M1b-2b-ii.)
     */
    /** Upload the firmware; returns the ARM reset vector (fw[0]) on success, 0 on failure. */
    static int uploadFirmware()
    {
        Address e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.bin"));
        if (e.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("  fw NOT FOUND in ramfs\n"));
            return 0;
        }
        Address src = e.plus(16L).loadAddress();                // firmware bytes (baked into the image)
        long len = e.plus(24L).loadLong();
        log(Magic.bytes("fw bytes="), (int) len);

        // The bus is still at the ~400 kHz ID clock; raise it so a 609 KB upload isn't glacial.
        Sdio.setClock(25000000);
        log(Magic.bytes("sdio clk ctl="), Sdio.control1());

        int first = src.loadInt();                   // the ARM reset vector (first word)
        if (!bpWrite(RAMBASE, src, len))
        {
            board.bcm2711.Uart.write(Magic.bytes("  fw upload FAILED\n"));
            return 0;
        }
        int rb0 = bpRead32(RAMBASE);
        log(Magic.bytes("fw[0] readback="), rb0);
        board.bcm2711.Uart.write(rb0 == first ? Magic.bytes("  fw upload OK\n") : Magic.bytes("  fw upload MISMATCH\n"));
        return rb0 == first ? first : 0;
    }

    /**
     * M1b-2b-ii: process the NVRAM .txt (strip comment/blank lines, join key=value with NUL, pad to 4) and
     * write it at {@code rambase + ramsize - varsz}, then the length token at {@code rambase + ramsize - 4}
     * ({@code (~(varsz/4)<<16) | (varsz/4 & 0xFFFF)}) — the trailer the chip ROM reads to find the NVRAM.
     */
    static boolean uploadNvram(int ramsize)
    {
        Address e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.raspberrypi,4-model-b.txt"));
        if (e.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("  nvram NOT FOUND\n"));
            return false;
        }
        Address src = e.plus(16L).loadAddress();
        int srcLen = (int) e.plus(24L).loadLong();
        Address buf = Heap.allocData(8192);
        int varsz = processNvram(src, srcLen, buf);
        long addr = RAMBASE + (long) ramsize - (long) varsz;
        log(Magic.bytes("nvram varsz="), varsz);
        board.bcm2711.Uart.write(Magic.bytes("  nvram addr="));
        VM.printHex(addr);
        board.bcm2711.Uart.putc(0x0A);
        if (!bpWrite(addr, buf, varsz))
        {
            board.bcm2711.Uart.write(Magic.bytes("  nvram upload FAILED\n"));
            return false;
        }
        int varsizew = varsz / 4;
        int token = (~varsizew << 16) | (varsizew & 0xFFFF);
        bpWrite32(RAMBASE + (long) ramsize - 4L, token);
        log(Magic.bytes("nvram token="), token);
        return true;
    }

    /** Strip comment/blank lines from the NVRAM at {@code src}, join {@code key=value} entries with NUL into
     *  {@code dst}, pad to a 4-byte boundary. Returns the padded length (varsz). */
    private static int processNvram(Address src, int srcLen, Address dst)
    {
        int di = 0;
        int i = 0;
        while (i < srcLen)
        {
            int ls = i;
            while (i < srcLen && (src.plus(i).loadUnsignedByte() & 0xFF) != 0x0A)   // to end of line
            {
                i = i + 1;
            }
            int le = i;
            if (i < srcLen) { i = i + 1; }                               // skip '\n'
            if (le > ls && (src.plus(le - 1).loadUnsignedByte() & 0xFF) == 0x0D)   // trim '\r'
            {
                le = le - 1;
            }
            int cs = ls;
            while (cs < le && ((src.plus(cs).loadUnsignedByte() & 0xFF) == 0x20 || (src.plus(cs).loadUnsignedByte() & 0xFF) == 0x09))
            {
                cs = cs + 1;                                             // skip leading whitespace
            }
            if (cs >= le || (src.plus(cs).loadUnsignedByte() & 0xFF) == 0x23)      // blank or '#' comment
            {
                continue;
            }
            int j = cs;
            while (j < le)
            {
                dst.plus(di).store((byte) src.plus(j).loadUnsignedByte());
                di = di + 1;
                j = j + 1;
            }
            dst.plus(di).store((byte) 0);                                   // NUL terminate each entry
            di = di + 1;
        }
        while ((di & 3) != 0)                                           // pad to 4 bytes
        {
            dst.plus(di).store((byte) 0);
            di = di + 1;
        }
        return di;
    }

    /**
     * Boot the firmware: write the reset vector to backplane address 0 (where the CR4 fetches it), bring the
     * ARM CR4 out of reset with the CPU RUNNING (resetcore, CPUHALT cleared), enable F2 and wait for the
     * firmware to report F2-ready — the moment it comes alive.
     */
    static void bootFirmware(int ramsize, int rstvec)
    {
        bpWrite32(0x0L, rstvec);                          // CR4 boots from address 0
        armCr4Run(ARMCR4_WRAP);                           // out of reset, CPU running
        // F2's block size, for block-mode CMD53 writes of frames over 512 bytes (byte mode's count field is
        // 9 bits). Never set before: every transfer was byte mode. Read back and printed -- measured, not assumed.
        Sdio.cmd52Write(F0, FBR2_BLKSIZE, F2_BLOCK & 0xFF);
        Sdio.cmd52Write(F0, FBR2_BLKSIZE + 1, (F2_BLOCK >> 8) & 0xFF);
        log(Magic.bytes("f2 blksize="), Sdio.cmd52Read(F0, FBR2_BLKSIZE) | (Sdio.cmd52Read(F0, FBR2_BLKSIZE + 1) << 8));
        Sdio.cmd52Write(F0, CCCR_IOEx, (1 << F1) | (1 << F2));   // enable the WLAN data function
        int tries = 0;
        while ((Sdio.cmd52Read(F0, CCCR_IORx) & (1 << F2)) == 0 && tries < 2000)
        {
            tries = tries + 1;
            VM.delayMs(1);
        }
        int ior = Sdio.cmd52Read(F0, CCCR_IORx);
        log(Magic.bytes("after release ior="), ior);
        if ((ior & (1 << F2)) == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: F2 not ready (firmware did not come up)\n"));
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: FIRMWARE UP (F2 ready)\n"));
        requestHtClock();
        setF2Watermarks();
        readFirstFrame();                                // M1c-1: dump the firmware's first SDPCM frame
    }

    /**
     * Request the HT (high-throughput) backplane clock and keep it. Until 2026-11-08 the host only ever
     * requested ALP -- {@link #HT_AVAIL_REQ} was defined and never written -- while brcmfmac holds HT
     * ({@code brcmf_sdio_htclk}, {@code CLK_AVAIL}) for every pass of its DPC, the loop that moves frames in both
     * directions. This driver never sleeps the bus, so the request stands for the life of the link. The 43455
     * here is not save/restore capable (PMU chipcontrol 3 bit 2 read 0 on the Pi, 2026-11-08), so this HT path
     * -- not sr_init + KSO -- is the one brcmfmac takes for it. Linux parity: it did not by itself fix the held
     * frames (the transmit ORDER did -- see sendFrame). Read back and printed.
     */
    private static void requestHtClock()
    {
        Sdio.cmd52Write(F1, CHIPCLKCSR, HT_AVAIL_REQ);
        int tries = 0;
        while ((Sdio.cmd52Read(F1, CHIPCLKCSR) & HT_AVAIL) == 0 && tries < 50)
        {
            tries = tries + 1;
            VM.delayMs(1);
        }
        log(Magic.bytes("HT clock csr="), Sdio.cmd52Read(F1, CHIPCLKCSR));
    }

    // SDIO device FIFO watermarks (brcmfmac sdio.h / sdio.c, the 43455 case of brcmf_sdio_firmware_callback).
    private static final int SBSDIO_WATERMARK = 0x10008;        // F2 watermark: the device's TX path (to the host)
    private static final int SBSDIO_DEVICE_CTL = 0x10009;
    private static final int SBSDIO_DEVCTL_F2WM_ENAB = 0x10;
    private static final int SBSDIO_FUNC1_MESBUSYCTRL = 0x1001D; // the device's RX path (from the host)
    private static final int CY_43455_F2_WATERMARK = 0x60;       // in 4-byte words
    private static final int CY_43455_MESBUSYCTRL = 0x50 | 0x80; // MES watermark | SBSDIO_MESBUSYCTRL_ENAB

    /**
     * Program the 43455's SDIO FIFO watermarks exactly as Linux brcmfmac does right after F2 comes up: the F2
     * watermark (with F2WM_ENAB in the device control register) and the MES busy control for the other
     * direction. This driver had never written any of the three, leaving them at their reset values. Linux
     * parity: like the HT request it did not by itself fix the held frames (see sendFrame). Read back and printed.
     */
    private static void setF2Watermarks()
    {
        Sdio.cmd52Write(F1, SBSDIO_WATERMARK, CY_43455_F2_WATERMARK);
        int devctl = Sdio.cmd52Read(F1, SBSDIO_DEVICE_CTL);
        Sdio.cmd52Write(F1, SBSDIO_DEVICE_CTL, devctl | SBSDIO_DEVCTL_F2WM_ENAB);
        Sdio.cmd52Write(F1, SBSDIO_FUNC1_MESBUSYCTRL, CY_43455_MESBUSYCTRL);
        log(Magic.bytes("f2 watermark="), Sdio.cmd52Read(F1, SBSDIO_WATERMARK));
        log(Magic.bytes("devctl="), Sdio.cmd52Read(F1, SBSDIO_DEVICE_CTL));
        log(Magic.bytes("mesbusyctrl="), Sdio.cmd52Read(F1, SBSDIO_FUNC1_MESBUSYCTRL));
    }

    /**
     * M1c-1: enable SDIO interrupts, wait for the firmware's first SDPCM frame (it announces bus credits /
     * an initial event after boot), and dump the raw SDPCM header — the ground truth for the framing the TX
     * and BCDC layers need. hw header = [len:16][~len:16]; sw header = seq/channel/nextlen/dataoff/flow/maxseq.
     */
    static void readFirstFrame()
    {
        enableWifiIrq();                                 // M6: arm the SDIO card interrupt once; stays armed for
                                                         // every RX below (all frame reads go through waitFrameIrq)
        Address rb = Heap.allocData(256);
        int flen = waitFrameIrq(rb, 256, 1000);          // block until the firmware's first SDPCM frame arrives
        if (flen == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  F2 first frame TIMEOUT\n"));
            return;
        }
        int hw = rb.loadInt();                       // [len:16][~len:16]
        int sw = rb.plus(4).loadInt();                   // seq(8) | channel+flags(8) | nextlen(8) | dataoff(8)
        int sw2 = rb.plus(8).loadInt();                  // flow(8) | maxseq(8) | reserved(16)
        int len = hw & 0xFFFF;
        int nlen = (hw >> 16) & 0xFFFF;
        log(Magic.bytes("rx hw="), hw);
        log(Magic.bytes("rx sw="), sw);
        log(Magic.bytes("rx sw2="), sw2);
        board.bcm2711.Uart.write(((len ^ nlen) & 0xFFFF) == 0xFFFF
                ? Magic.bytes("  SDPCM frame valid (len/~len match)\n")
                : Magic.bytes("  SDPCM header invalid (len/~len mismatch)\n"));

        verIoctl();                                      // M1c-2: ask the firmware its version
    }

    private static int txSeq = 0;                        // SDPCM tx sequence (credit-limited by maxseq)
    private static final int WLC_GET_VAR = 262;          // ioctl: read a named iovar

    /**
     * M1c-2: send a BCDC {@code WLC_GET_VAR "ver"} ioctl over the SDPCM control channel and dump the
     * firmware's version string from the response — the "we're talking to live firmware" proof.
     * Frame = SDPCM hdr(12) + BCDC hdr(16) + data. BCDC hdr = [cmd:u32][len:u32][flags:u32 (id&lt;&lt;16)][status:u32].
     */
    static void verIoctl()
    {
        Address tx = Heap.allocData(512);                   // zeroed
        int dataLen = 192;                               // response buffer for the version string
        tx.plus(28).store((byte) 0x76);                     // "ver\0" (BCDC data, right after the 16-byte BCDC hdr)
        tx.plus(29).store((byte) 0x65);
        tx.plus(30).store((byte) 0x72);
        tx.plus(31).store((byte) 0);
        tx.plus(12).store(WLC_GET_VAR);             // BCDC cmd
        tx.plus(16).store(dataLen);                 // BCDC len (buffer size the fw fills)
        tx.plus(20).store(1 << 16);                 // BCDC flags: id=1 (high 16), GET (no SET bit)
        tx.plus(24).store(0);                       // BCDC status
        int frameLen = 12 + 16 + dataLen;                // 220
        tx.plus(4).store((byte) (txSeq & 0xFF));              // SDPCM sw: seq
        tx.plus(5).store((byte) 0);                         //   channel 0 = control
        tx.plus(6).store((byte) 0);                         //   nextlen
        tx.plus(7).store((byte) 12);                        //   dataoffset = 12 (payload after the 12-byte hdr)
        store16(tx.plus(0), frameLen);                       // SDPCM hw: len
        store16(tx.plus(2), ~frameLen);                      //   ~len
        if (!Sdio.cmd53Write(F2, 0, true, tx.toLong(), 1, (frameLen + 3) & ~3))
        {
            board.bcm2711.Uart.write(Magic.bytes("  ioctl tx FAILED\n"));
            return;
        }
        txSeq = txSeq + 1;
        reqId = reqId + 1;                               // ver consumed id 1; scan ioctls get 2,3,...

        Address rx = Heap.allocData(1024);
        int rlen = recvCtrl(rx, 1024, 1);                // ver uses BCDC id 1
        if (rlen == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  no ioctl response\n"));
            return;
        }
        int doff = rx.plus(7).loadUnsignedByte() & 0xFF;           // response data offset
        log(Magic.bytes("resp len="), rlen);
        log(Magic.bytes("resp doff="), doff);
        board.bcm2711.Uart.write(Magic.bytes("wifi: ver = "));
        dumpAscii(rx.plus(doff + 16), 180);                  // value = after sdpcm(doff) + bcdc(16)

        readMac();                                       // now, while the firmware is idle: read after link-up
                                                         // it could miss its 1.5 s window, and the 4-way then
                                                         // ran on a zero MAC (wrong MIC, no msg3, no network)
        bringUp();                                       // CLM, events, WLC_UP, then join
    }

    private static int reqId = 1;                        // BCDC request id (echoed in the response)
    private static final int WLC_UP = 2;                 // ioctl: bring the interface up
    private static final int WLC_DOWN = 3;               // ioctl: bring the interface down
    private static final int WLC_GET_BSSID = 23;         // ioctl: the associated BSSID
    private static final int WLC_SET_KEY = 45;           // ioctl: install a key (wsec_key)
    private static final int WLC_SET_VAR = 263;          // ioctl: set a named iovar

    static int irqCount;                                 // SDIO card interrupts serviced (IRQ-driven RX)

    /**
     * ISR — called from {@link vm.VM#schedule} when SPI 158 fires: the CYW43 has an F2 frame for us. Disable
     * the interrupt at the GIC (the reliable way to stop a level interrupt re-firing — masking at the SDHCI
     * didn't de-assert it) and return true so the scheduler posts WIFI_SEM; the handler task services the
     * frame and re-enables the SPI. No SDIO work in interrupt context.
     */
    public static boolean onIrq()
    {
        board.bcm2711.Gic.disableSpi(board.bcm2711.Bcm2711.SDIO_SPI);
        irqCount = irqCount + 1;
        return true;
    }

    /** Route the SDHCI controller's SPI to the GIC and enable the SDIO card interrupt (SDHCI + CCCR funcs). */
    // The chip only raises the SDIO card interrupt if its SDIOD-core interrupt mask is set (Plan9/Circle
    // ether4330 line 971): FrameInt = an F2 frame is ready. Without it, DAT1 is never asserted.
    private static final long SDIOD_CORE = 0x18004000L;   // SDIOD core base (from the EROM enumeration)
    private static final long SD_INTSTATUS = 0x20L;
    private static final long SD_INTMASK = 0x24L;
    private static final int SD_FRAMEINT = 1 << 6;        // an F2 frame is ready (RX)
    private static final int SD_MAILBOXINT = 1 << 7;
    private static final int SD_FCCHANGE = 1 << 5;
    private static final int SD_FCSTATE = 1 << 4;         // device-level flow control: no data while set
    private static final long SD_TOSBMAILBOX = 0x40L;
    private static final long SD_TOHOSTMAILBOXDATA = 0x4CL;
    private static final int SMB_INT_ACK = 1 << 1;
    private static final int HMB_DATA_FC = 0x0004;

    /** Per-attempt block for {@link #waitFrameIrq}: how long a single RX wait sleeps before returning 0 so its
     *  caller can re-check its own (longer) outer deadline / resend a request. Small enough that a periodic
     *  retransmit (e.g. TCP SYN every 500 ms) still fires roughly on time. */
    private static final int RX_WAIT_MS = 200;

    /** Enable IRQ-driven WiFi RX: tell the chip to assert on F2-frame/mailbox/fc events, enable the SDIO
     *  function + card interrupts, and route the SDHCI SPI to the GIC. Leaves the card interrupt armed. */
    static void enableWifiIrq()
    {
        bpWrite32(SDIOD_CORE + SD_INTMASK, SD_FRAMEINT | SD_MAILBOXINT | SD_FCCHANGE);
        Sdio.cmd52Write(F0, CCCR_IEN, 0x07);             // master + F1 + F2 function interrupts
        Sdio.enableCardInt();                            // IRPT_MASK bit8 (status) + IRPT_EN bit8 (armed)
        board.bcm2711.Gic.initSpi(board.bcm2711.Bcm2711.SDIO_SPI);
    }

    private static boolean waitFrameWarned;              // the after-start misuse below was reported once

    /**
     * Block on the SDIO card interrupt until the chip signals a frame, then read it. The task sleeps in
     * semWait (no busy-polling) until the ISR ({@link #onIrq}) disables the GIC SPI and posts WIFI_SEM; we
     * then read the frame, clear the SDIOD + SDHCI interrupt status (de-asserting DAT1), and re-enable the
     * GIC SPI for the next frame.
     *
     * <p>A glom superframe is several frames in one transfer: its first subframe is returned in place of the
     * descriptor, and each later call returns the next one without waiting -- they have already arrived.
     */
    public static int waitFrameIrq(Address dst, int cap, int ms)
    {
        if (net.RxQueues.active)                         // the RX task owns the chip: a second reader would
        {                                                //   steal its frames and its interrupt
            if (!waitFrameWarned)
            {
                waitFrameWarned = true;
                board.bcm2711.Uart.write(Magic.bytes("wifi: BUG waitFrameIrq called after the rx task started\n"));
            }
            vm.VMScheduler.kernelSleep(ms);              // as if nothing arrived: callers loop on 0
            return 0;
        }
        if (glomPending())
        {
            int sub = glomNext(dst, cap);                // the rest of a superframe read earlier
            if (sub > 0)
            {
                net.NetStats.rxSync(dst, sub);
                return sub;
            }
        }
        if (!vm.VMScheduler.semWaitTimeout(vm.VM.WIFI_SEM, ms))
        {
            return 0;                                    // no frame within the timeout (GIC SPI still armed)
        }
        int len = readFrameOnce(dst, cap);               // the ISR posted: read the frame the chip signaled
        if (len > 0 && isGlomDesc(dst))
        {
            int total = readSuperframe(dst, len);        // it follows the descriptor in the FIFO
            len = 0;
            if (total > 0)
            {
                bringupGlom = bringupGlom + 1;
                bringupGlomSubs = bringupGlomSubs + glomCount;
                len = glomNext(dst, cap);
            }
        }
        net.NetStats.rxSync(dst, len);
        int ints = bpRead32(SDIOD_CORE + SD_INTSTATUS);
        serviceInts(ints);                               // mailbox ACK + flow control, before the status is cleared
        bpWrite32(SDIOD_CORE + SD_INTSTATUS, ints & ~SD_FCSTATE);   // write-1-to-clear (FC_STATE is a level)
        Sdio.clearCardInt();                             // clear the SDHCI card-int status (de-assert DAT1)
        board.bcm2711.Gic.enableSpi(board.bcm2711.Bcm2711.SDIO_SPI);   // re-arm the SPI the ISR disabled
        return len;
    }

    /**
     * Bring the interface up and join: load the CLM (regulatory/PHY data the radio needs before it can
     * associate), turn on the join-phase events, WLC_UP, then {@link #join}. The firmware scans for the SSID
     * itself on WLC_SET_SSID, so no listing scan runs first (one used to: ~6 s of escan results printed as
     * {@code SSID:} lines at every boot, which nothing used).
     */
    static void bringUp()
    {
        clmLoad();
        enableEvents();
        readCtrl(sendBcdc(WLC_UP, Address.zero(), 0, true));
        VM.delayMs(100);
        join();
    }

    /** Read a big-endian u32 (firmware event_msg fields are network-order). */
    private static int beU32(Address a)
    {
        return ((a.loadUnsignedByte() & 0xFF) << 24) | ((a.plus(1).loadUnsignedByte() & 0xFF) << 16)
                | ((a.plus(2).loadUnsignedByte() & 0xFF) << 8) | (a.plus(3).loadUnsignedByte() & 0xFF);
    }

    private static final int WLC_SET_INFRA    = 20;      // 1 = infrastructure (BSS), not IBSS
    private static final int WLC_SET_AUTH     = 22;      // 0 = open-system auth
    private static final int WLC_SET_SSID     = 26;      // associate to a wlc_ssid_t {len, SSID[32]}
    private static final int WLC_SET_WSEC     = 134;     // 0 = open, 4 = AES/CCMP
    private static final int WLC_SET_WPA_AUTH = 165;     // 0 = no WPA, 0x80 = WPA2-PSK
    private static final int WLC_SET_PM       = 86;      // power save: 0 = off (PM_OFF), 1 = PM_MAX, 2 = PM_FAST
    private static final int WLC_SET_WSEC_PMK = 268;     // set passphrase/PMK (wsec_pmk_t)

    /**
     * M2/M5 join: read {@code ssid=}/{@code psk=} from {@code /etc/wifi.conf}. If a psk is present, configure
     * WPA2-PSK (in-chip supplicant offload); otherwise an open BSS. Fire WLC_SET_SSID and wait for E_LINK up,
     * then run DHCP.
     */
    static void join()
    {
        Address e = VM.fileFind(Magic.bytes("/etc/wifi.conf"));
        if (e.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("  no /etc/wifi.conf\n"));
            return;
        }
        Address conf = e.plus(16L).loadAddress();
        int flen = (int) e.plus(24L).loadLong();
        Address ssid = Heap.allocData(48);
        int sl = confValue(conf, flen, Magic.bytes("ssid"), ssid);
        Address psk = Heap.allocData(72);
        int pl = confValue(conf, flen, Magic.bytes("psk"), psk);
        if (sl == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  wifi.conf: no ssid\n"));
            return;
        }
        staSsid = ssid;                                  // kept for the WPA2 PMK (PBKDF2 salt = SSID)
        staSsidLen = sl;
        staPsk = psk;
        staPskLen = pl;
        board.bcm2711.Uart.write(Magic.bytes("wifi: join "));
        dumpRaw(ssid, sl);

        if (pl > 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  security: WPA2-PSK\n"));
            setupWpa2(psk, pl);
        }
        else
        {
            board.bcm2711.Uart.write(Magic.bytes("  security: open\n"));
            setInt(WLC_SET_INFRA, 1);
            setInt(WLC_SET_WSEC, 0);
            setInt(WLC_SET_AUTH, 0);
            setInt(WLC_SET_WPA_AUTH, 0);
        }

        Address ss = Heap.allocData(48);                    // wlc_ssid_t: len (u32) + SSID[32]
        ss.store(sl);
        int i = 0;
        while (i < sl)
        {
            ss.plus(4 + i).store((byte) ssid.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        long joinT = Magic.readCNTPCT_EL0();
        boolean ok = false;
        while (!ok && wpaAttempts < JOIN_ATTEMPTS)
        {
            if (wpaAttempts > 0)
            {
                board.bcm2711.Uart.write(Magic.bytes("wifi: join again\n"));
                readCtrl(sendBcdc(WLC_DISASSOC, Address.zero(), 0, true));
                VM.delayMs(1000);
            }
            wpaAttempts = wpaAttempts + 1;
            heldLen = 0;                                 // a kept frame belongs to the attempt that read it
            ok = associate(ss);
        }
        if (!ok)
        {
            return;
        }
        wpaJoinMs = (int) ((Magic.readCNTPCT_EL0() - joinT) * 1000L / Magic.readCNTFRQ_EL0());
        board.bcm2711.Uart.write(Magic.bytes("wifi: join "));
        VM.printDec(wpaJoinMs);
        board.bcm2711.Uart.write(Magic.bytes(" ms, eapol msg1 x"));
        VM.printDec(wpaMsg1);
        board.bcm2711.Uart.write(Magic.bytes(", msg2 x"));
        VM.printDec(wpaMsg2);
        board.bcm2711.Uart.write(Magic.bytes(", kept from the join wait "));
        VM.printDec(wpaHeld);
        board.bcm2711.Uart.write(Magic.bytes(", attempts "));
        VM.printDec(wpaAttempts);
        board.bcm2711.Uart.putc(0x0A);
        if (staPskLen > 0 && WPA2_OFFLOAD)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: offload link up (fw ran the 4-way)\n"));
        }
        VM.delayMs(500);
        dhcp();
    }

    private static final int WLC_DISASSOC = 52;          // ioctl: leave the BSS
    private static final int JOIN_ATTEMPTS = 3;
    public static int wpaAttempts;                       // associations tried at boot (1 = the first one held)

    /**
     * One association: WLC_SET_SSID, wait for link-up, then (WPA2) the host 4-way. False if the link never came
     * up or the handshake failed; {@link #join} then leaves the BSS and tries again.
     */
    private static boolean associate(Address ss)
    {
        readCtrl(sendBcdc(WLC_SET_SSID, ss, 36, true));
        boolean linked = waitLink();
        board.bcm2711.Uart.write(linked ? Magic.bytes("wifi: JOINED\n") : Magic.bytes("wifi: join timeout\n"));
        if (!linked)
        {
            return false;
        }
        if (staPskLen == 0 || WPA2_OFFLOAD)
        {
            return true;
        }
        if (!macKnown())
        {
            readMac();                                   // the boot-time read failed: once more, before fourWay
            if (!macKnown())
            {
                board.bcm2711.Uart.write(Magic.bytes("wifi: no mac, cannot key\n"));
                return false;
            }
        }
        if (!fourWay())
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: 4-way failed\n"));
            return false;
        }
        return true;
    }

    /** True once {@link #readMac} has stored a MAC that is not all zero (the PTK and msg2 are built on it). */
    private static boolean macKnown()
    {
        if (ourMac.isZero())
        {
            return false;
        }
        int i = 0;
        while (i < 6)
        {
            if (ourMac.plus(i).loadUnsignedByte() != 0)
            {
                return true;
            }
            i = i + 1;
        }
        return false;
    }

    static Address staSsid;                              // SSID bytes (PBKDF2 salt)
    static int staSsidLen;
    static Address staPsk;                               // passphrase bytes
    static int staPskLen;

    /** true = let the firmware's own supplicant run the 4-way (hand it the PMK via WLC_SET_WSEC_PMK, like
     *  brcmfmac's PSK offload); false = run the host 4-way ({@link #fourWay}) and relay EAPOL ourselves.
     *  Offload avoids the host-EAPOL-TX path entirely (which draws no msg3 on this firmware). */
    static final boolean WPA2_OFFLOAD = false;

    /**
     * Configure WPA2-PSK. Common: ciphers (wsec=AES/CCMP), wpa_auth=WPA2-PSK, open 802.11 auth, and the
     * association RSN IE (`wpaie`, brcmf_configure_wpaie) so it matches the handshake. Then either
     * {@link #WPA2_OFFLOAD} — enable the firmware supplicant (`sup_wpa=1`) and hand it the 32-byte PMK
     * (WLC_SET_WSEC_PMK, brcmf_set_pmk), so the chip runs the whole 4-way after WLC_SET_SSID and E_LINK-up
     * means "authenticated" — or the host path, where we associate unkeyed and run {@link #fourWay} ourselves.
     */
    static void setupWpa2(Address psk, int pl)
    {
        setInt(WLC_DOWN, 0);
        setInt(WLC_SET_INFRA, 1);                        // infrastructure BSS
        setInt(WLC_SET_WSEC, 4);                         // AES/CCMP
        setInt(WLC_SET_AUTH, 0);                         // open 802.11 auth
        setInt(WLC_SET_WPA_AUTH, 0x80);                  // WPA2-PSK
        // Power save OFF. With the firmware's default PM the AP buffers our unicast frames while the radio
        // dozes; Pi run 15 measured TCP segments held for 9-12 s and then delivered in one burst while the RX
        // task polled an empty chip every 100 ms -- long enough for the TLS server to give up.
        setInt(WLC_SET_PM, 0);
        setInt(WLC_UP, 0);                               // WLC_SET_SSID then associates
        // Set the association RSN IE (brcmf_configure_wpaie) BEFORE the join, so the firmware advertises the
        // SAME IE in the assoc request that we echo in EAPOL msg2's key data (host path) / the handshake.
        iovarData(Magic.bytes("wpaie"), rsnIe(), 22);
        // Precompute the PMK NOW, before WLC_SET_SSID associates. PBKDF2(4096) is by far the slowest step;
        // computing it here (while the AP is idle, pre-association) keeps it OFF the msg1->msg2 critical path,
        // so we answer the AP's first msg1 in milliseconds. The AP restarts the 4-way with a fresh ANonce
        // ~once a second and silently drops stale replies, so a slow first response loses the race forever.
        staPmk = new byte[32];
        byte[] pass = heapBytes(psk, pl);
        byte[] ssid = heapBytes(staSsid, staSsidLen);
        // Generic PBKDF2 over crypto/Digest's SHA-1, not a WPA2-private copy: one derivation serves
        // this and javax.crypto.SecretKeyFactory. It also builds ONE Hmac and reuses it, where the
        // one-shot it replaced re-padded the key on every one of these 4096 iterations.
        crypto.Pbkdf2.derive(crypto.Digest.SHA1, pass, pass.length, ssid, ssid.length, 4096, staPmk, 32);
        board.bcm2711.Uart.write(Magic.bytes("wifi: pmk ready\n"));
        if (WPA2_OFFLOAD)
        {
            iovarInt(Magic.bytes("sup_wpa"), 1);         // enable the firmware supplicant (may be a no-op)
            setPmk(staPmk);                              // WLC_SET_WSEC_PMK: the chip runs the 4-way itself
            board.bcm2711.Uart.write(Magic.bytes("wifi: offload pmk set\n"));
        }
    }

    static byte[] staPmk;                        // PBKDF2(psk, ssid), precomputed pre-association

    /**
     * Hand the firmware the 32-byte PMK for its own supplicant (brcmf_set_wsec → WLC_SET_WSEC_PMK). Struct
     * {@code brcmf_wsec_pmk_le} = key_len(u16 LE) + flags(u16 LE) + key[128], zeroed; key_len=32, flags=0
     * (a raw PMK, not the WSEC_PASSPHRASE flag), 32-byte PMK, the whole 132-byte struct sent.
     */
    private static void setPmk(byte[] pmk)
    {
        Address b = Heap.allocData(140);                    // allocData zeroes; struct is 132 bytes
        b.store(32);                            // key_len = 32 (LE u16) | flags = 0 (LE u16)
        int i = 0;
        while (i < 32)
        {
            b.plus(4 + i).store((byte) (pmk[i] & 0xFF));
            i = i + 1;
        }
        readCtrl(sendBcdc(WLC_SET_WSEC_PMK, b, 132, true));
    }

    private static Address rxbuf;                        // shared RX frame buffer for the handshake

    /**
     * The WPA2 4-way handshake, run in Java. PMK = PBKDF2(psk, ssid); receive msg1 (ANonce, AP MAC); derive
     * PTK (KCK/KEK/TK); send msg2 (SNonce + MIC + our RSN IE); receive msg3 (verify MIC, AES-unwrap the GTK);
     * send msg4; install the pairwise TK and the GTK with WLC_SET_KEY. Then CCMP flows and DHCP can run.
     */
    static boolean fourWay()
    {
        byte[] pmk = staPmk;                             // precomputed in setupWpa2 (off the critical path)

        rxbuf = Heap.allocData(2048);
        byte[] apMac = new byte[6];
        Address m1 = recvEapol(apMac);                      // msg1
        if (m1.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: no eapol msg1\n"));
            return false;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: eapol msg1\n"));
        wpaMsg1 = wpaMsg1 + 1;

        byte[] aNonce = heapBytes(m1.plus(17), 32);
        int keyLen = ((m1.plus(7).loadUnsignedByte() & 0xFF) << 8) | (m1.plus(8).loadUnsignedByte() & 0xFF);
        byte[] replay = heapBytes(m1.plus(9), 8);            // echo msg1's replay counter in msg2

        // AA = msg1's Ethernet source = the AP's BSSID (verified against a monitor capture: for this
        // association it IS the over-the-air BSSID). NOTHING slow may go between receiving msg1 and sending
        // msg2: the AP restarts the handshake with a fresh ANonce roughly once a second, so a stale reply is
        // silently dropped. (An earlier get_bssid/get_channel ioctl here added ~14 s of latency and lost the
        // race every time, even though the MIC was correct.)
        byte[] aa = apMac;

        byte[] sNonce = new byte[32];
        genNonce(sNonce);
        byte[] ptk = new byte[48];
        derivePtk(pmk, aa, heapBytes(ourMac, 6), aNonce, sNonce, ptk);
        byte[] kck = slice(ptk, 0, 16);
        byte[] kek = slice(ptk, 16, 16);
        byte[] tk = slice(ptk, 32, 16);
        board.bcm2711.Uart.write(Magic.bytes("wifi: ptk derived\n"));

        // msg2 key data: the RSN IE we set as `wpaie`, which the association request carried. It is not read
        // back from the firmware: right after link-up the firmware can leave ioctls unanswered for ~7 s (Pi,
        // 2026-11-14), so that read cost 4 s, came back empty, and this same IE then keyed the link.
        byte[] ie = rsnIe();
        int ieLen = ie.length;

        // Send msg2 and wait for msg3. A frame with no MIC bit is a (re)transmitted msg1 — re-derive the PTK
        // from its ANonce/replay and resend msg2. A frame with the MIC bit is msg3.
        Address m3 = Address.zero();
        int tries = 0;
        while (tries < 6 && m3.isZero())
        {
            sendEapol(aa, 0x010a, replay, keyLen, sNonce, ie, ieLen, kck);
            wpaMsg2 = wpaMsg2 + 1;
            board.bcm2711.Uart.write(Magic.bytes("wifi: eapol msg2 sent\n"));
            Address f = recvEapol(apMac);
            if (f.isZero())
            {
                tries = tries + 1;
                continue;
            }
            int ki = ((f.plus(5).loadUnsignedByte() & 0xFF) << 8) | (f.plus(6).loadUnsignedByte() & 0xFF);
            if ((ki & 0x100) == 0)                       // no MIC → another msg1: re-derive and resend msg2
            {
                wpaMsg1 = wpaMsg1 + 1;
                aNonce = heapBytes(f.plus(17), 32);
                replay = heapBytes(f.plus(9), 8);
                derivePtk(pmk, aa, heapBytes(ourMac, 6), aNonce, sNonce, ptk);
                kck = slice(ptk, 0, 16);
                kek = slice(ptk, 16, 16);
                tk = slice(ptk, 32, 16);
                tries = tries + 1;
                continue;
            }
            m3 = f;                                      // has MIC → msg3
        }
        if (m3.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: no eapol msg3\n"));
            return false;
        }
        int eapLen3 = ((m3.plus(2).loadUnsignedByte() & 0xFF) << 8) | (m3.plus(3).loadUnsignedByte() & 0xFF);
        byte[] frame3 = heapBytes(m3, 4 + eapLen3);
        byte[] rxMic = slice(frame3, 81, 16);
        int j = 0;
        while (j < 16)
        {
            frame3[81 + j] = 0;                          // zero the MIC field before recomputing
            j = j + 1;
        }
        byte[] calc = new byte[20];
        crypto.Hmac.mac(crypto.Digest.SHA1, kck, 16, frame3, frame3.length, calc);
        if (cmp(calc, rxMic, 16) != 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: msg3 MIC mismatch\n"));
            return false;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: msg3 MIC ok\n"));

        int kdLen = ((m3.plus(97).loadUnsignedByte() & 0xFF) << 8) | (m3.plus(98).loadUnsignedByte() & 0xFF);
        byte[] wrapped = heapBytes(m3.plus(99), kdLen);
        byte[] kd = new byte[kdLen - 8];
        if (!crypto.KeyWrap.unwrap(kek, wrapped, kdLen, kd))
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: GTK unwrap failed\n"));
            return false;
        }
        byte[] gtk = new byte[16];
        int gtkId = extractGtk(kd, kd.length, gtk);
        if (gtkId < 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: no GTK KDE\n"));
            return false;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: GTK unwrapped\n"));

        byte[] replay3 = heapBytes(m3.plus(9), 8);           // echo msg3's replay counter in msg4
        byte[] zero32 = new byte[32];
        byte[] empty = new byte[0];
        sendEapol(apMac, 0x030a, replay3, keyLen, zero32, empty, 0, kck);   // msg4
        board.bcm2711.Uart.write(Magic.bytes("wifi: eapol msg4 sent\n"));

        installKey(0, tk, 16, apMac, true);              // pairwise TK, bound to the AP
        installKey(gtkId, gtk, 16, new byte[6], false);  // group GTK
        board.bcm2711.Uart.write(Magic.bytes("wifi: keys installed\n"));
        Supplicant.established(ie, ieLen, kck, kek);     // later re-handshakes run on the RX task
        return true;
    }

    /**
     * Wait (~8 s) for an EAPOL frame (ethertype 0x888E) from the AP; fills {@code apMac} (source), returns
     * the EAPOL-Key body's heap offset, or 0 on timeout. A frame {@link #holdIfEapol} kept is taken first.
     */
    private static Address recvEapol(byte[] apMac)
    {
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 8L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            int len;
            if (heldLen > 0)
            {
                len = heldLen;
                copyBytes(heldEapol, rxbuf, len);
                heldLen = 0;
            }
            else
            {
                len = waitFrameIrq(rxbuf, 2048, RX_WAIT_MS);
            }
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            Address eth = eapolEth(rxbuf);
            if (eth.isZero())
            {
                continue;
            }
            int i = 0;
            while (i < 6)
            {
                apMac[i] = (byte) eth.plus(6 + i).loadUnsignedByte();
                i = i + 1;
            }
            return eth.plus(14);
        }
        return Address.zero();
    }

    /** The Ethernet header of a received frame if it carries EAPOL (ethertype 0x888E), else 0. */
    private static Address eapolEth(Address frame)
    {
        if ((frame.plus(5).loadUnsignedByte() & 0x0F) == 0)
        {
            return Address.zero();                                   // an ioctl ack
        }
        Address eth = frame.plus(frame.plus(7).loadUnsignedByte() & 0xFF);
        eth = eth.plus(4 + (eth.plus(3).loadUnsignedByte() & 0xFF) * 4);   // past the BDC header and its data offset
        if (((eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF)) != 0x888E)
        {
            return Address.zero();
        }
        return eth;
    }

    private static Address heldEapol;                    // an EAPOL frame read before fourWay was listening
    private static int heldLen;                          // its length; 0 = none held

    /**
     * Keep {@code frame} for {@link #recvEapol} if it is EAPOL. The AP sends msg1 the moment it has
     * associated us, which can be before or beside the E_LINK event {@link #waitLink} is reading for; a
     * reader that dropped it left the handshake waiting ~1 s for the AP to send msg1 again -- or, if the
     * retry was lost too, failed the join. The newest frame wins: a resent msg1 carries the current ANonce.
     */
    private static void holdIfEapol(Address frame, int len)
    {
        if (eapolEth(frame).isZero() || len <= 0 || len > 2048)
        {
            return;
        }
        if (heldEapol.isZero())
        {
            heldEapol = Heap.allocData(2048);
        }
        copyBytes(frame, heldEapol, len);
        heldLen = len;
        wpaHeld = wpaHeld + 1;
    }

    private static void copyBytes(Address src, Address dst, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst.plus(i).store((byte) src.plus(i).loadUnsignedByte());
            i = i + 1;
        }
    }

    public static int wpaMsg1;                           // boot 4-way: EAPOL msg1 frames received (1 = no resend)
    public static int wpaMsg2;                           // ... msg2 frames sent
    public static int wpaHeld;                           // ... EAPOL frames kept from the join wait / an ioctl wait
    public static int wpaJoinMs;                         // ... WLC_SET_SSID to keys installed, in ms

    /** Build + send an EAPOL-Key frame; computes the MIC over the frame (MIC field zeroed) with KCK when the
     *  key-info MIC bit is set. */
    static void sendEapol(byte[] apMac, int keyInfo, byte[] replay, int keyLen, byte[] nonce,
            byte[] keyData, int kdLen, byte[] kck)
    {
        int eapLen = 95 + kdLen;                         // EAPOL body length (after the 4-byte EAPOL header)
        int total = 14 + 4 + eapLen;
        byte[] f = new byte[total];
        put(f, 0, apMac, 6);
        put(f, 6, heapBytes(ourMac, 6), 6);
        f[12] = (byte) 0x88;
        f[13] = (byte) 0x8e;
        int o = 14;
        f[o] = 2;                                        // EAPOL version 2
        f[o + 1] = 3;                                    // type = EAPOL-Key
        f[o + 2] = (byte) (eapLen >>> 8);
        f[o + 3] = (byte) eapLen;
        f[o + 4] = 2;                                    // descriptor type = RSN
        f[o + 5] = (byte) (keyInfo >>> 8);
        f[o + 6] = (byte) keyInfo;
        f[o + 7] = (byte) (keyLen >>> 8);
        f[o + 8] = (byte) keyLen;
        put(f, o + 9, replay, 8);
        put(f, o + 17, nonce, 32);
        // IV(16)/RSC(8)/reserved(8)/MIC(16) stay zero
        f[o + 97] = (byte) (kdLen >>> 8);
        f[o + 98] = (byte) kdLen;
        put(f, o + 99, keyData, kdLen);
        if ((keyInfo & 0x100) != 0)                      // MIC bit set: fill it
        {
            byte[] eap = slice(f, o, 4 + eapLen);
            byte[] mic = new byte[20];
            crypto.Hmac.mac(crypto.Digest.SHA1, kck, 16, eap, eap.length, mic);
            int i = 0;
            while (i < 16)
            {
                f[o + 81 + i] = mic[i];
                i = i + 1;
            }
        }
        Address buf = Heap.allocData(total + 16);
        int i = 0;
        while (i < total)
        {
            buf.plus(i).store((byte) (f[i] & 0xFF));
            i = i + 1;
        }
        txDataP(buf, total, 0);                          // EAPOL at BDC priority 0 (AC_BE) -- brcmfmac classifies
                                                         // a bare 0x888E frame via cfg80211_classify8021d, which
                                                         // returns 0; priority 7 (AC_VO) is dropped by the firmware
                                                         // on the unauthorized controlled port (why our EAPOL never
                                                         // left the chip while all priority-0 data did)
    }

    /** Find the GTK KDE (OUI 00-0f-ac, type 1) in unwrapped key data; copies the 16-byte GTK to {@code out}
     *  and returns its key id, or -1 if absent. */
    static int extractGtk(byte[] kd, int len, byte[] out)
    {
        int p = 0;
        while (p + 2 <= len)
        {
            int type = kd[p] & 0xFF;
            int l = kd[p + 1] & 0xFF;
            if (type == 0xdd && l >= 6 && (kd[p + 2] & 0xFF) == 0x00 && (kd[p + 3] & 0xFF) == 0x0f
                    && (kd[p + 4] & 0xFF) == 0xac && (kd[p + 5] & 0xFF) == 0x01)
            {
                int id = kd[p + 6] & 0x03;               // GTK KDE: OUI(3) type(1) keyinfo(1) rsv(1) GTK
                int i = 0;
                while (i < 16)
                {
                    out[i] = kd[p + 8 + i];
                    i = i + 1;
                }
                return id;
            }
            if (l == 0)
            {
                break;
            }
            p = p + 2 + l;
        }
        return -1;
    }

    /** Install a key via WLC_SET_KEY (brcmf_wsec_key_le): CCMP algo, ea = peer for the pairwise key. */
    static void installKey(int index, byte[] key, int keyLen, byte[] ea, boolean pairwise)
    {
        Address b = Heap.allocData(200);                    // zeroed
        b.store(index);
        b.plus(4).store(keyLen);
        int i = 0;
        while (i < keyLen)
        {
            b.plus(8 + i).store((byte) (key[i] & 0xFF));
            i = i + 1;
        }
        b.plus(112).store(4);                       // algo = CRYPTO_ALGO_AES_CCM
        b.plus(116).store(pairwise ? 2 : 0);        // flags = WSEC_PRIMARY_KEY for the pairwise key
        i = 0;
        while (i < 6)
        {
            b.plus(156 + i).store((byte) (ea[i] & 0xFF));     // ea = peer MAC (pairwise) / zero (group)
            i = i + 1;
        }
        int id = sendBcdc(WLC_SET_KEY, b, 164, true);
        if (WlanPoll.onRxTask())
        {
            return;                                      // the RX task is the one that would read the ack: a
        }                                                //   later recvCtrl skips it by id
        readCtrl(id);
    }

    static byte[] slice(byte[] src, int off, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = src[off + i];
            i = i + 1;
        }
        return b;
    }

    /** PTK = PRF(PMK, "Pairwise key expansion", min(AA,SPA)||max(AA,SPA)||min(An,Sn)||max(An,Sn), 48). */
    static void derivePtk(byte[] pmk, byte[] aa, byte[] spa, byte[] an, byte[] sn, byte[] ptk)
    {
        byte[] data = new byte[76];
        boolean aaFirst = cmp(aa, spa, 6) < 0;
        put(data, 0, aaFirst ? aa : spa, 6);
        put(data, 6, aaFirst ? spa : aa, 6);
        boolean anFirst = cmp(an, sn, 32) < 0;
        put(data, 12, anFirst ? an : sn, 32);
        put(data, 44, anFirst ? sn : an, 32);
        byte[] label = Magic.bytes("Pairwise key expansion");
        crypto.Prf.sha1(pmk, 32, label, label.length, data, 76, ptk, 48);
    }

    /** A pseudo-random SNonce from the counter-timer (good enough for a one-shot join). */
    static void genNonce(byte[] out)
    {
        int i = 0;
        while (i < 32)
        {
            long t = Magic.readCNTPCT_EL0() + (long) i * 0x9E3779B9L;
            out[i] = (byte) (t ^ (t >>> 13) ^ (t >>> 27));
            i = i + 1;
        }
    }

    /** Copy {@code len} bytes from heap {@code addr} into a fresh byte[]. */
    static byte[] heapBytes(Address addr, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = (byte) addr.plus(i).loadUnsignedByte();
            i = i + 1;
        }
        return b;
    }

    static void put(byte[] dst, int off, byte[] src, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[off + i] = src[i];
            i = i + 1;
        }
    }

    /** Unsigned lexicographic compare of the first {@code len} bytes. */
    static int cmp(byte[] a, byte[] b, int len)
    {
        int i = 0;
        while (i < len)
        {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0)
            {
                return d;
            }
            i = i + 1;
        }
        return 0;
    }

    /** Wait up to ~15 s for the E_LINK up event, tracing join events; returns true if the link came up. */
    private static boolean waitLink()
    {
        Address rx = Heap.allocData(2048);
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 15L;
        boolean linked = false;
        while (Magic.readCNTPCT_EL0() < endT && !linked)
        {
            int len = waitFrameIrq(rx, 2048, RX_WAIT_MS);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
            {
                continue;                                // ioctl ack
            }
            holdIfEapol(rx, len);                        // msg1 can come before E_LINK: keep it for fourWay
            linked = parseJoinEvent(rx, len);
        }
        return linked;
    }

    /** GET a named iovar into {@code out}; returns the number of bytes the firmware returned (0 on failure). */
    private static int iovarGet(byte[] name, byte[] out)
    {
        return iovarGet(name, out, 1500);
    }

    /** {@link #iovarGet}, waiting up to {@code ms} for the reply. */
    private static int iovarGet(byte[] name, byte[] out, int ms)
    {
        Address g = Heap.allocData(256);
        int p = putStr(g, name);
        int id = sendBcdc(WLC_GET_VAR, g, p + 128, false);
        Address rx = Heap.allocData(512);
        int len = recvCtrl(rx, 512, id, ms);
        if (len == 0)
        {
            return 0;
        }
        int doff = rx.plus(7).loadUnsignedByte() & 0xFF;
        int dlen = rx.plus(doff + 4).loadInt();          // BCDC len = returned data length
        if (dlen < 0 || dlen > out.length)
        {
            dlen = out.length;
        }
        Address data = rx.plus(doff + 16);
        int i = 0;
        while (i < dlen)
        {
            out[i] = (byte) data.plus(i).loadUnsignedByte();
            i = i + 1;
        }
        return dlen;
    }

    /** Set a plain integer iovar: "&lt;name&gt;\0" + value(u32). (For interface 0, brcmfmac uses this — the
     *  "bsscfg:" prefix is only for non-zero interface indices.) */
    private static void iovarInt(byte[] name, int value)
    {
        Address b = Heap.allocData(64);
        int p = putBytes(b, 0, name);
        b.plus(p).store((byte) 0);                          // NUL after the iovar name
        p = p + 1;
        b.plus(p).store(value);
        p = p + 4;
        readCtrl(sendBcdc(WLC_SET_VAR, b, p, true));
    }

    /** Set a byte-array iovar: "&lt;name&gt;\0" + raw {@code data[0..len]}. Used for {@code wpaie} (the RSN IE). */
    private static void iovarData(byte[] name, byte[] data, int len)
    {
        Address b = Heap.allocData(256);
        int p = putBytes(b, 0, name);
        b.plus(p).store((byte) 0);                          // NUL after the iovar name
        p = p + 1;
        int i = 0;
        while (i < len)
        {
            b.plus(p + i).store((byte) data[i]);
            i = i + 1;
        }
        p = p + len;
        readCtrl(sendBcdc(WLC_SET_VAR, b, p, true));
    }

    /**
     * The STA's RSN IE for WPA2-PSK/CCMP: group=CCMP, pairwise=CCMP, akm=PSK, caps=0. This exact IE is set as
     * the firmware's association IE (`wpaie`) before the join AND placed in EAPOL msg2's key data — the AP
     * rejects msg2 (and never sends msg3) unless its key-data RSN IE matches the one we sent at association.
     */
    private static byte[] rsnIe()
    {
        // caps = 0x000c (last two bytes, little-endian): PTKSA replay-counter field 0b11, no PMF. This MUST
        // match the RSN capabilities the firmware puts in its association request (confirmed 0x000c via a
        // monitor capture) -- the AP compares msg2's key-data RSN IE against the assoc RSN IE and silently
        // drops msg2 on any mismatch. caps 0x0000 here made every (otherwise valid) msg2 rejected -> no msg3.
        byte[] ie = {
            (byte) 0x30, 0x14, 0x01, 0x00, 0x00, 0x0f, (byte) 0xac, 0x04, 0x01, 0x00, 0x00, 0x0f,
            (byte) 0xac, 0x04, 0x01, 0x00, 0x00, 0x0f, (byte) 0xac, 0x02, 0x0c, 0x00
        };
        return ie;
    }

    /** Set a bsscfg-scoped integer iovar on interface 0: "bsscfg:&lt;name&gt;\0" + index(u32) + value(u32). */
    private static void bsscfgInt(byte[] name, int value)
    {
        Address b = Heap.allocData(64);
        int p = putBytes(b, 0, Magic.bytes("bsscfg:"));
        p = putBytes(b, p, name);
        b.plus(p).store((byte) 0);                          // NUL after the iovar name
        p = p + 1;
        b.plus(p).store(0);                         // bsscfg index 0
        p = p + 4;
        b.plus(p).store(value);
        p = p + 4;
        readCtrl(sendBcdc(WLC_SET_VAR, b, p, true));
    }

    /** Find a {@code key=value} line in wifi.conf and copy the value (to CR/LF/end) into {@code dst}; returns
     *  its length, or 0 if the key is absent. */
    private static int confValue(Address conf, int flen, byte[] key, Address dst)
    {
        int i = 0;
        while (i < flen)
        {
            int k = 0;
            int j = i;
            while (k < key.length && j < flen && (conf.plus(j).loadUnsignedByte() & 0xFF) == (key[k] & 0xFF))
            {
                k = k + 1;
                j = j + 1;
            }
            if (k == key.length && j < flen && (conf.plus(j).loadUnsignedByte() & 0xFF) == 0x3D)   // "key="
            {
                j = j + 1;
                int n = 0;
                while (j < flen && n < 63)
                {
                    int c = conf.plus(j).loadUnsignedByte() & 0xFF;
                    if (c == 0x0A || c == 0x0D || c == 0)
                    {
                        break;
                    }
                    dst.plus(n).store((byte) c);
                    n = n + 1;
                    j = j + 1;
                }
                return n;
            }
            while (i < flen && (conf.plus(i).loadUnsignedByte() & 0xFF) != 0x0A)   // skip to the next line
            {
                i = i + 1;
            }
            i = i + 1;
        }
        return 0;
    }

    static Address ourMac;                               // heap addr of our 6-byte station MAC

    /** M3: read our station MAC (iovar cur_etheraddr) into {@link #ourMac} and print it. */
    static void readMac()
    {
        Address g = Heap.allocData(64);
        int p = putStr(g, Magic.bytes("cur_etheraddr"));
        int id = sendBcdc(WLC_GET_VAR, g, p + 6, false);
        Address rx = Heap.allocData(256);
        int len = recvCtrl(rx, 256, id);
        ourMac = Heap.allocData(8);
        if (len == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  no mac\n"));
            return;
        }
        int doff = rx.plus(7).loadUnsignedByte() & 0xFF;
        Address m = rx.plus(doff + 16);
        int i = 0;
        while (i < 6)
        {
            ourMac.plus(i).store((byte) m.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: mac "));
        i = 0;
        while (i < 6)
        {
            printHex2(ourMac.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 5)
            {
                board.bcm2711.Uart.putc(0x3A);           // ':'
            }
            i = i + 1;
        }
        board.bcm2711.Uart.putc(0x0A);
    }

    /** Send an 802.3 frame over the SDPCM data channel (2) with a 4-byte BDC header (proto ver 2, no pad). */
    public static void txData(Address frame, int flen)
    {
        txDataP(frame, flen, 0);
    }

    /** Send an 802.3 frame over the SDPCM data channel with an explicit BDC priority (EAPOL uses 7, the
     *  802.1D network-control priority, like dhd/brcmfmac). */
    static void txDataP(Address frame, int flen, int prio)
    {
        Address tx = Heap.allocData(2048);
        tx.plus(5).store((byte) 2);                         // channel 2 = data
        tx.plus(6).store((byte) 0);
        tx.plus(7).store((byte) 12);                        // dataoffset
        tx.plus(12).store((byte) 0x20);                     // BDC flags = proto ver (2) << 4
        tx.plus(13).store((byte) prio);                     // priority
        tx.plus(14).store((byte) 0);                        // flags2 (interface 0)
        tx.plus(15).store((byte) 0);                        // data_offset = 0 words
        int i = 0;
        while (i < flen)
        {
            tx.plus(16 + i).store((byte) frame.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        int total = 12 + 4 + flen;
        net.NetStats.out(frame, flen);
        store16(tx.plus(0), total);
        store16(tx.plus(2), ~total);
        sendFrame(tx, total);
    }

    /**
     * Put one built SDPCM frame ({@code total} bytes at {@code tx}) on the bus. Every caller hands over a buffer of
     * its own (fresh from {@code Heap.allocData}), so a frame that cannot go yet is QUEUED by address, not copied.
     *
     * <p>The firmware takes a frame only inside its transmit window ({@link #haveCredit}) and, for a data frame,
     * only while the flow-control mask in its last SDPCM header leaves that frame's precedence open. Before the
     * RX task runs, nobody reads the frames that move the window, so bring-up keeps the old rule: wait up to
     * 20 ms, then send. Once it runs, a frame that may not go is queued and the RX task sends it the moment a
     * received header opens the window ({@link #flushTxLocked}), as brcmfmac's DPC does from its txq. Until
     * 2026-11-08 the driver sent past the window after 20 ms, and the RX task (which reads the frames that open
     * it) sent at once. That was suspected of a transmit stall -- a lone last frame reaching the peer 230 ms or
     * more late -- and REFUTED: the stall persisted with the queue in place and it held no frame; the frame
     * reaches the chip whole and on time (the hold is past the bus, still open). The queue stays because it is
     * what the protocol requires, not because it fixed that.
     *
     * <p>A task other than the RX task still waits (up to 20 ms) for the window first, which keeps a writer from
     * filling the queue. The RX task never waits and never writes here: what it sends (the stack answers a
     * segment on the task that read it -- a SYN-ACK, the ACK of a FIN, a RST) is QUEUED, and goes on the bus only
     * when its poll has drained the chip to an empty read and cleared the interrupt status (WlanPoll.poll) --
     * brcmfmac's DPC order: readframes, clear, then sendfromq. Written straight after the frame read instead,
     * the chip took the frame whole (WFRAMEBC 0) and held it until a later bus event: measured 2026-11-08,
     * 17-35 of ~65 connects took 1-7 s (every SYN-ACK of a connection reaching the peer in one burst), and
     * delaying the write ~3 ms (a UART print before the SYN-ACK) took that to 0 of 82.
     */
    private static void sendFrame(Address tx, int total)
    {
        if (!WlanPoll.running())
        {
            waitCredit(tx);
            WlanPoll.busLock();
            writeFrameLocked(tx, total);
            WlanPoll.busUnlock();
            return;
        }
        boolean rxTask = WlanPoll.onRxTask();
        if (!rxTask)
        {
            waitCredit(tx);
        }
        WlanPoll.busLock();
        if (!rxTask)
        {
            flushTxLocked();                             // what is queued goes first: frames keep their order
        }
        if (!rxTask && txqCount == 0 && maySend(tx))
        {
            writeFrameLocked(tx, total);
        }
        else if (txqCount < TXQ)
        {
            int k = (txqHead + txqCount) & (TXQ - 1);
            txqBuf[k] = tx.toLong();
            txqLen[k] = total;
            txqAt[k] = Magic.readCNTPCT_EL0();
            txqGc[k] = vm.VM.gcCollections();
            if (txqCount == 0)
            {
                txqSince = Magic.readCNTPCT_EL0();
            }
            txqCount = txqCount + 1;
            txQueued = txQueued + 1;
        }
        else
        {
            txDropped = txDropped + 1;                   // a full queue: drop, as a full brcmfmac txq does
        }
        WlanPoll.busUnlock();
    }

    /**
     * Write one frame on F2, stamping the next SDPCM sequence number. The caller holds the bus.
     *
     * <p>A frame of up to 512 bytes goes in BYTE mode; a longer one in BLOCK mode, padded to whole
     * {@link #F2_BLOCK}-byte blocks (the dongle takes the length from the SDPCM header, as brcmfmac pads). Byte
     * mode's count field is 9 bits: a 556-byte frame was sent as "44 bytes" while the host clocked 556, which
     * left the data line busy -- every later bus operation then sat out a ~3.6 s timeout, and the frame itself
     * never arrived (the first HTTP response body, 2026-11-07). The buffer must hold the padding: txDataP's is
     * 2048 bytes, and an Ethernet frame (<= 1514 + 16) rounds up to at most 1536.
     */
    private static void writeFrameLocked(Address tx, int total)
    {
        tx.plus(4).store((byte) (txSeq & 0xFF));              // SDPCM seq
        int len = (total + 3) & ~3;
        if (len <= 512)
        {
            Sdio.cmd53Write(F2, 0, true, tx.toLong(), 1, len);
        }
        else
        {
            Sdio.cmd53Write(F2, 0, true, tx.toLong(), (len + F2_BLOCK - 1) / F2_BLOCK, F2_BLOCK);
        }
        txSeq = txSeq + 1;
    }

    // The transmit queue: frames that were not allowed on the bus yet, oldest first. Touched only under the bus
    // lock. TXQ is a power of two.
    private static final int TXQ = 64;
    private static final int TXQ_FORCE_MS = 250;         // a frame held this long is sent anyway (see flushTxLocked)
    private static long[] txqBuf = new long[TXQ];
    private static int[] txqLen = new int[TXQ];
    private static long[] txqAt = new long[TXQ];         // CNTPCT when each frame was queued
    private static long[] txqGc = new long[TXQ];         // ... and the collection count then
    public static int txqLongAcrossGc;                   // MEASUREMENT: waits >= 100 ms that spanned a collection
    public static long txqWaitTicks;                     // MEASUREMENT: summed queue time of the frames sent
    public static long txqWaitMax;                       // ... the longest one
    public static int txqFlushes;                        // ... flushes that sent at least one frame
    public static int txqWaitsOver100;                   // ... frames that waited 100 ms or more
    private static int txqHead = 0;
    private static int txqCount = 0;
    private static long txqSince;                        // CNTPCT when the current head became the head
    public static int txQueued = 0;                      // frames that waited in the queue
    public static int txForced = 0;                      // frames sent past the window after TXQ_FORCE_MS
    public static int txDropped = 0;                            // frames dropped because the queue was full

    /**
     * Send what the queue holds, oldest first, while the firmware allows it. The caller holds the bus; the RX
     * task calls this after every frame it reads (each header carries the window and the flow-control mask)
     * and when the chip is empty, which with the 100 ms watchdog bounds how long a frame can wait.
     *
     * <p>The window arrives in frame headers and flow control in interrupts (see {@link #clearFrameInts}), both
     * of which wake the RX task, so a blocked queue drains on an interrupt. A head frame still blocked after
     * {@link #TXQ_FORCE_MS} is sent anyway, printed and counted in {@link #txForced}: a backstop against a
     * signal we do not decode stranding the queue for ever, and the printed line says which one it was.
     */
    static void flushTxLocked()
    {
        if (txqCount > 0)
        {
            txqFlushes = txqFlushes + 1;
        }
        while (txqCount > 0)
        {
            Address tx = Address.fromLong(txqBuf[txqHead]);
            if (!maySend(tx))
            {
                long held = Magic.readCNTPCT_EL0() - txqSince;
                if (held < Magic.readCNTFRQ_EL0() * TXQ_FORCE_MS / 1000L)
                {
                    return;
                }
                txForced = txForced + 1;
                board.bcm2711.Uart.write(Magic.bytes("wifi: tx forced past the window seq="));
                VM.printDec(txSeq & 0xFF);
                board.bcm2711.Uart.write(Magic.bytes(" max="));
                VM.printDec(txMax);
                board.bcm2711.Uart.write(Magic.bytes(" fc=0x"));
                VM.printHex(txFc);
                if (txFcState)
                {
                    board.bcm2711.Uart.write(Magic.bytes(" FC_STATE"));
                }
                board.bcm2711.Uart.putc(0x0A);
            }
            writeFrameLocked(tx, txqLen[txqHead]);
            long w = Magic.readCNTPCT_EL0() - txqAt[txqHead];
            txqWaitTicks = txqWaitTicks + w;
            if (w > txqWaitMax)
            {
                txqWaitMax = w;
            }
            if (w >= Magic.readCNTFRQ_EL0() / 10L)
            {
                txqWaitsOver100 = txqWaitsOver100 + 1;
                if (vm.VM.gcCollections() != txqGc[txqHead])
                {
                    txqLongAcrossGc = txqLongAcrossGc + 1;
                }
            }
            txqBuf[txqHead] = 0L;
            txqHead = (txqHead + 1) & (TXQ - 1);
            txqCount = txqCount - 1;
            txqSince = Magic.readCNTPCT_EL0();
        }
    }

    static Address ourIp;                                // our leased IP (4 bytes)
    static Address gwIp;                                 // default gateway
    static Address snMask;                               // subnet mask
    static Address dnsIp;                                // DNS server

    /**
     * M4: full DHCP handshake — Discover → Offer → Request → Ack. The Offer proposes an IP + server-id; we
     * Request it (echoing both), the Ack commits the lease and carries subnet(1)/gateway(3)/DNS(6). Stores
     * and prints the lease. All frames go over the SDPCM data path built in M3.
     */
    static void dhcp()
    {
        if (ourMac.isZero())                                // normally read earlier (before the WPA2 4-way); open
        {                                                // networks reach dhcp() without that step
            readMac();
        }
        ourIp = Heap.allocData(4);
        gwIp = Heap.allocData(4);
        snMask = Heap.allocData(4);
        dnsIp = Heap.allocData(4);
        Address serverId = Heap.allocData(4);
        Address buf = Heap.allocData(512);
        Address buf2 = Heap.allocData(512);
        Address rx = Heap.allocData(2048);

        // Retry the handshake: right after the WPA2 4-way the first exchange can drop a packet (we saw OFFER
        // arrive but the REQUEST draw no ACK). Resend the REQUEST a few times for a lost ACK, and re-DISCOVER
        // if that still fails. The xid is a fixed constant, so retries stay in the same DHCP transaction.
        int disc = 0;
        while (disc < 4)
        {
            disc = disc + 1;
            board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp discover\n"));
            txData(buf, buildDhcp(buf, 1, Address.zero(), Address.zero()));      // DISCOVER
            if (!recvDhcp(rx, 2, ourIp, serverId, Address.zero(), Address.zero(), Address.zero()))   // wait OFFER (type 2)
            {
                board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp no offer, retry\n"));
                continue;
            }
            board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp offer ip "));
            printIp(ourIp);
            board.bcm2711.Uart.putc(0x0A);

            boolean acked = false;
            int rq = 0;
            while (rq < 3 && !acked)                      // REQUEST -> ACK, resending REQUEST on a lost ACK
            {
                rq = rq + 1;
                board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp request\n"));
                txData(buf2, buildDhcp(buf2, 3, ourIp, serverId));   // REQUEST (opt 50 req-ip, opt 54 server-id)
                acked = recvDhcp(rx, 5, ourIp, serverId, snMask, gwIp, dnsIp);   // wait ACK (type 5)
            }
            if (!acked)
            {
                board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp no ack, re-discover\n"));
                continue;
            }

            board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp ACK ip "));
            printIp(ourIp);
            board.bcm2711.Uart.write(Magic.bytes("\n  gateway "));
            printIp(gwIp);
            board.bcm2711.Uart.write(Magic.bytes("\n  subnet  "));
            printIp(snMask);
            board.bcm2711.Uart.write(Magic.bytes("\n  dns     "));
            printIp(dnsIp);
            board.bcm2711.Uart.putc(0x0A);

            arpPing();                                    // M4: ARP the gateway, then ping it
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: dhcp FAILED after retries\n"));
    }

    static Address gwMac;                                // gateway MAC (6 bytes), learned via ARP

    /** M4: resolve the gateway's MAC via ARP, then ICMP-ping it — the first "IP packets flow" proof. */
    static void arpPing()
    {
        gwMac = Heap.allocData(8);
        if (!arpResolve(gwIp, gwMac))
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: arp no reply\n"));
            return;
        }
        // Publish the interface config to the reusable stack (net.*): net.Tcp and the M3 socket layer read it.
        net.Ip.ourMac = ourMac;
        net.Ip.ourIp = ourIp;
        net.Ip.netMask = snMask;
        net.Ip.gwMac = gwMac;
        board.bcm2711.Uart.write(Magic.bytes("wifi: gateway mac "));
        int i = 0;
        while (i < 6)
        {
            printHex2(gwMac.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 5)
            {
                board.bcm2711.Uart.putc(0x3A);
            }
            i = i + 1;
        }
        board.bcm2711.Uart.putc(0x0A);

        if (ping(gwIp, gwMac))
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: ping reply from "));
            printIp(gwIp);
            board.bcm2711.Uart.putc(0x0A);
        }
        else
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: ping no reply\n"));
        }
        WlanPoll.start();                                // the RX task owns the chip from here (NAPI-style)
        net.Sntp.sync();                                 // the wall clock from a time server, before any program runs
        // Connectivity is up and net.Ip is published. Run the built-in DNS+HTTP demo only when this is the
        // WiFi showcase; when the OS brought the interface up for a launched program, stop here and let the
        // program drive the sockets (M3: stock java.net over net.Tcp).
        if (runDemo)
        {
            dns();                                       // M4: resolve a hostname -> IP -> HTTP GET
        }
    }

    /** true = the bring-up runs its built-in DNS+HTTP-GET showcase; false = bring up connectivity only (the
     *  OS launches a program that owns the sockets). Set by VM.run before Wifi.bringUp. */
    public static boolean runDemo = true;

    /** M4: resolve example.com via DNS and print the A record. */
    static void dns()
    {
        Address ip = Heap.allocData(4);
        if (dnsResolve(Magic.bytes("example.com"), ip))
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: dns example.com = "));
            printIp(ip);
            board.bcm2711.Uart.putc(0x0A);
            httpGet(ip, Magic.bytes("example.com"));      // M4: TCP connect + HTTP GET (acceptance test)
        }
        else
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: dns no reply\n"));
        }
    }

    /**
     * M4 acceptance test: TCP 3-way handshake to {@code serverIp}:80, then an HTTP/1.0 GET, printing the
     * response. Piggybacks the request on the handshake-completing ACK. Tracks seq/ack, ACKs received data,
     * and ACKs the server's FIN. Off-subnet, so all frames go to the gateway MAC. Uses retry + fast-drain.
     */
    static void httpGet(Address serverIp, byte[] host)
    {
        // The TCP state machine now lives in the reusable net.Tcp stack; this is just an HTTP client over it
        // (and the pattern the M3 java.net.Socket path will follow through the VM socket helpers).
        int h = net.Tcp.connect(serverIp, 80);
        if (h < 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("wifi: tcp no synack\n"));
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: tcp connected\n"));

        Address req = Heap.allocData(256);
        int reqLen = buildHttpReq(req, host);
        net.Tcp.write(h, req, 0, reqLen);
        board.bcm2711.Uart.write(Magic.bytes("wifi: GET sent -----\n"));

        Address rxbuf = Heap.allocData(2048);
        int total = 0;
        while (true)
        {
            int n = net.Tcp.read(h, rxbuf, 0, 2048);
            if (n <= 0)                                  // n<0 = peer FIN/EOF, n==0 = timeout with no data
            {
                break;
            }
            printText(rxbuf, n);
            total = total + n;
        }
        net.Tcp.close(h);
        board.bcm2711.Uart.write(Magic.bytes("\n----- http done, "));
        VM.printDec(total);
        board.bcm2711.Uart.write(Magic.bytes(" bytes\n"));
    }

    private static int lastIhl;                          // IP header length of the last frame findTcp accepted
    private static int lastIpTotal;                      // IP total length of that frame

    /** Return the TCP header address of a received segment from {@code serverIp} to our {@code sport} (0 if
     *  none this read). Records the frame's IP IHL / total-length in {@link #lastIhl}/{@link #lastIpTotal}. */
    private static Address findTcp(Address rx, Address serverIp, int sport)
    {
        int len = waitFrameIrq(rx, 2048, RX_WAIT_MS);
        if (len == 0)
        {
            return Address.zero();
        }
        if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
        {
            return Address.zero();
        }
        Address eth = rx.plus(rx.plus(7).loadUnsignedByte() & 0xFF);
        eth = eth.plus(4 + (eth.plus(3).loadUnsignedByte() & 0xFF) * 4);
        if (((eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF)) != 0x0800)
        {
            return Address.zero();
        }
        Address ip = eth.plus(14);
        if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 6)
        {
            return Address.zero();                                   // not TCP
        }
        if (!ipEq(ip.plus(12), serverIp))
        {
            return Address.zero();
        }
        lastIhl = (ip.loadUnsignedByte() & 0x0F) * 4;
        lastIpTotal = (ip.plus(2).loadUnsignedByte() & 0xFF) << 8 | (ip.plus(3).loadUnsignedByte() & 0xFF);
        Address tcp = ip.plus(lastIhl);
        if (((tcp.plus(2).loadUnsignedByte() & 0xFF) << 8 | (tcp.plus(3).loadUnsignedByte() & 0xFF)) != sport)
        {
            return Address.zero();                                   // not our connection
        }
        return tcp;
    }

    /** Build a TCP segment (Ethernet via gwMac / IP / TCP + payload) and send it. */
    private static void sendTcp(Address serverIp, int sport, int dport, int seq, int ack, int flags,
            Address payload, int plen)
    {
        Address buf = Heap.allocData(2048);
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) gwMac.plus(i).loadUnsignedByte());
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address tcp = buf.plus(34);
        be16(tcp.plus(0), sport);
        be16(tcp.plus(2), dport);
        be32(tcp.plus(4), seq);
        be32(tcp.plus(8), ack);
        tcp.plus(12).store((byte) 0x50);                    // data offset = 5 (20-byte header)
        tcp.plus(13).store((byte) flags);
        be16(tcp.plus(14), 64240);                           // window
        be16(tcp.plus(16), 0);                               // checksum (fill below)
        be16(tcp.plus(18), 0);                               // urgent pointer
        i = 0;
        while (i < plen)
        {
            tcp.plus(20 + i).store((byte) payload.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        int tcpLen = 20 + plen;
        be16(tcp.plus(16), tcpCksum(tcp, tcpLen, ourIp, serverIp));
        ip.store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        be16(ip.plus(2), 20 + tcpLen);
        be16(ip.plus(4), 0);
        be16(ip.plus(6), 0x4000);                            // don't fragment
        ip.plus(8).store((byte) 64);
        ip.plus(9).store((byte) 6);                         // protocol = TCP
        be16(ip.plus(10), 0);
        copy4(ourIp, ip.plus(12));
        copy4(serverIp, ip.plus(16));
        be16(ip.plus(10), ipCksum(ip, 20));
        txData(buf, 14 + 20 + tcpLen);
    }

    /** TCP checksum: one's complement over the IPv4 pseudo-header + the TCP segment (checksum field must be 0). */
    private static int tcpCksum(Address tcp, int tcpLen, Address srcIp, Address dstIp)
    {
        int sum = 0;
        sum = sum + ((srcIp.loadUnsignedByte() & 0xFF) << 8 | (srcIp.plus(1).loadUnsignedByte() & 0xFF));
        sum = sum + ((srcIp.plus(2).loadUnsignedByte() & 0xFF) << 8 | (srcIp.plus(3).loadUnsignedByte() & 0xFF));
        sum = sum + ((dstIp.loadUnsignedByte() & 0xFF) << 8 | (dstIp.plus(1).loadUnsignedByte() & 0xFF));
        sum = sum + ((dstIp.plus(2).loadUnsignedByte() & 0xFF) << 8 | (dstIp.plus(3).loadUnsignedByte() & 0xFF));
        sum = sum + 6;                                   // zero byte + protocol
        sum = sum + tcpLen;
        int i = 0;
        while (i + 1 < tcpLen)
        {
            sum = sum + ((tcp.plus(i).loadUnsignedByte() & 0xFF) << 8 | (tcp.plus(i + 1).loadUnsignedByte() & 0xFF));
            i = i + 2;
        }
        if (i < tcpLen)
        {
            sum = sum + ((tcp.plus(i).loadUnsignedByte() & 0xFF) << 8);   // odd trailing byte
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Build "GET / HTTP/1.0\r\nHost: <host>\r\nConnection: close\r\n\r\n"; returns its length. */
    private static int buildHttpReq(Address buf, byte[] host)
    {
        int p = putBytes(buf, 0, Magic.bytes("GET / HTTP/1.0\r\nHost: "));
        p = putBytes(buf, p, host);
        p = putBytes(buf, p, Magic.bytes("\r\nConnection: close\r\n\r\n"));
        return p;
    }

    /** Copy {@code s} into {@code buf} at offset {@code p}; returns the new offset. */
    private static int putBytes(Address buf, int p, byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            buf.plus(p).store((byte) (s[i] & 0xFF));
            p = p + 1;
            i = i + 1;
        }
        return p;
    }

    /** Write {@code len} bytes to the UART, keeping CR/LF/TAB + printable ASCII (else '.'). */
    private static void printText(Address addr, int len)
    {
        int i = 0;
        while (i < len)
        {
            int c = addr.plus(i).loadUnsignedByte() & 0xFF;
            if (c == 0x0A || c == 0x0D || c == 0x09 || (c >= 0x20 && c < 0x7F))
            {
                board.bcm2711.Uart.putc(c);
            }
            else
            {
                board.bcm2711.Uart.putc(0x2E);
            }
            i = i + 1;
        }
    }

    /** Store a 32-bit big-endian value. */
    private static void be32(Address a, int v)
    {
        a.store((byte) ((v >> 24) & 0xFF));
        a.plus(1).store((byte) ((v >> 16) & 0xFF));
        a.plus(2).store((byte) ((v >> 8) & 0xFF));
        a.plus(3).store((byte) (v & 0xFF));
    }

    /** Read a 32-bit big-endian value. */
    private static int readBe32(Address a)
    {
        return ((a.loadUnsignedByte() & 0xFF) << 24) | ((a.plus(1).loadUnsignedByte() & 0xFF) << 16)
                | ((a.plus(2).loadUnsignedByte() & 0xFF) << 8) | (a.plus(3).loadUnsignedByte() & 0xFF);
    }

    /**
     * Send a DNS A-query for {@code host} to {@code dnsIp} (via the gateway) and parse the first A record
     * into {@code ipOut}. Resends every 0.5 s, drains fast (the unicast-RX pattern). Returns true on answer.
     *
     * <p>Each lookup takes a fresh source port and query id (resends keep both), and an answer counts only if it
     * comes from {@code dnsIp} port 53 to that port with that id (RFC 5452). The port and id used to be the
     * constants 0xC000 and 0x1234 on every boot, and the destination port and source address went unchecked.
     */
    public static boolean dnsResolve(byte[] host, Address ipOut)
    {
        Address buf = Heap.allocData(256);
        int localPort = net.Ip.ephemeralPort();
        int id = (int) (net.Ip.nextRandom() & 0xFFFF);
        int flen = buildDnsQuery(buf, host, localPort, id);
        Address rx = Heap.allocData(2048);
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;
        // What the lookup SAW, printed only on failure: a failed lookup reaches the guest as a bare
        // UnknownHostException (InetSocketAddress drops the cause), so this is the one place the reason exists.
        int sends = 0;
        int frames = 0;
        int ipv4 = 0;
        int fromDns = 0;
        int toUs = 0;
        int idOk = 0;
        int q = net.RxQueues.active ? net.RxQueues.bindUdp(localPort) : -1;
        boolean found = false;
        while (!found && Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                txData(buf, flen);
                nextSend = now + freq / 2L;
                sends = sends + 1;
            }
            int len = rxFrame(q, rx, 2048);
            if (len == 0)
            {
                continue;
            }
            if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
            {
                continue;
            }
            frames = frames + 1;
            Address eth = rx.plus(rx.plus(7).loadUnsignedByte() & 0xFF);
            eth = eth.plus(4 + (eth.plus(3).loadUnsignedByte() & 0xFF) * 4);
            int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if (et == 0x0806)
            {
                net.Ip.answerArp(eth);                            // a stale gateway re-verifies us mid-query
                continue;
            }
            if (et != 0x0800)
            {
                continue;
            }
            ipv4 = ipv4 + 1;
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 17 || !net.Ip.eq(ip.plus(12), dnsIp))
            {
                continue;                                // not UDP, or not from our DNS server
            }
            fromDns = fromDns + 1;
            Address udp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            if (((udp.loadUnsignedByte() & 0xFF) << 8 | (udp.plus(1).loadUnsignedByte() & 0xFF)) != 53)
            {
                continue;                                // not from the DNS port
            }
            if (((udp.plus(2).loadUnsignedByte() & 0xFF) << 8 | (udp.plus(3).loadUnsignedByte() & 0xFF)) != localPort)
            {
                continue;                                // not to this lookup's port
            }
            toUs = toUs + 1;
            Address d = udp.plus(8);
            if (((d.loadUnsignedByte() & 0xFF) << 8 | (d.plus(1).loadUnsignedByte() & 0xFF)) != id)
            {
                continue;                                // id mismatch
            }
            idOk = idOk + 1;
            int ancount = (d.plus(6).loadUnsignedByte() & 0xFF) << 8 | (d.plus(7).loadUnsignedByte() & 0xFF);
            if (ancount < 1)
            {
                continue;
            }
            Address rr = skipName(d.plus(12)).plus(4);              // skip question: qname + qtype(2) + qclass(2)
            int a = 0;
            while (a < ancount)
            {
                rr = skipName(rr);                       // answer name (often a compression pointer)
                int type = (rr.loadUnsignedByte() & 0xFF) << 8 | (rr.plus(1).loadUnsignedByte() & 0xFF);
                int rdlen = (rr.plus(8).loadUnsignedByte() & 0xFF) << 8 | (rr.plus(9).loadUnsignedByte() & 0xFF);
                if (type == 1 && rdlen == 4)             // A record
                {
                    copy4(rr.plus(10), ipOut);
                    found = true;
                    break;
                }
                rr = rr.plus(10 + rdlen);
                a = a + 1;
            }
        }
        net.RxQueues.unbind(q);
        if (found)
        {
            return true;
        }
        board.bcm2711.Uart.write(Magic.bytes("dns: no A record for "));
        board.bcm2711.Uart.write(host);
        board.bcm2711.Uart.write(Magic.bytes(" in 6 s: sends="));
        VM.printDec(sends);
        board.bcm2711.Uart.write(Magic.bytes(" frames="));
        VM.printDec(frames);
        board.bcm2711.Uart.write(Magic.bytes(" ipv4="));
        VM.printDec(ipv4);
        board.bcm2711.Uart.write(Magic.bytes(" fromDns="));
        VM.printDec(fromDns);
        board.bcm2711.Uart.write(Magic.bytes(" toPort="));
        VM.printDec(toUs);
        board.bcm2711.Uart.write(Magic.bytes(" idOk="));
        VM.printDec(idOk);
        board.bcm2711.Uart.putc(0x0A);
        return false;
    }

    /**
     * Build a DNS A-record query for {@code host} from {@code localPort} with query id {@code id} into {@code buf};
     * returns the total frame length.
     */
    private static int buildDnsQuery(Address buf, byte[] host, int localPort, int id)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) gwMac.plus(i).loadUnsignedByte());       // dst = gateway (route off-subnet)
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address udp = buf.plus(34);
        Address d = buf.plus(42);
        be16(d.plus(0), id);                                 // id
        be16(d.plus(2), 0x0100);                             // flags: recursion desired
        be16(d.plus(4), 1);                                  // qdcount
        be16(d.plus(6), 0);
        be16(d.plus(8), 0);
        be16(d.plus(10), 0);
        int qn = encodeQname(d.plus(12), host);
        be16(d.plus(12 + qn), 1);                            // qtype = A
        be16(d.plus(12 + qn + 2), 1);                        // qclass = IN
        int dnsLen = 12 + qn + 4;
        be16(udp.plus(0), localPort);                        // src port (ephemeral)
        be16(udp.plus(2), 53);                               // dst port = DNS
        be16(udp.plus(4), 8 + dnsLen);
        be16(udp.plus(6), 0);                                // no UDP checksum
        ip.store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        be16(ip.plus(2), 20 + 8 + dnsLen);
        be16(ip.plus(4), 0);
        be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);
        ip.plus(9).store((byte) 17);
        be16(ip.plus(10), 0);
        copy4(ourIp, ip.plus(12));
        copy4(dnsIp, ip.plus(16));
        be16(ip.plus(10), ipCksum(ip, 20));
        return 14 + 20 + 8 + dnsLen;
    }

    /** Encode a hostname as DNS labels (len-prefixed, root-terminated) at {@code dst}; returns bytes written. */
    private static int encodeQname(Address dst, byte[] host)
    {
        int p = 0;
        int hp = 0;
        while (hp < host.length)
        {
            Address lenPos = dst.plus(p);                       // reserve the label-length byte
            p = p + 1;
            int seg = 0;
            while (hp < host.length && (host[hp] & 0xFF) != 0x2E)   // until '.' or end
            {
                dst.plus(p).store((byte) (host[hp] & 0xFF));
                p = p + 1;
                hp = hp + 1;
                seg = seg + 1;
            }
            lenPos.store((byte) seg);
            if (hp < host.length && (host[hp] & 0xFF) == 0x2E)
            {
                hp = hp + 1;                             // skip the dot
            }
        }
        dst.plus(p).store((byte) 0);                        // root label
        p = p + 1;
        return p;
    }

    /** Advance past a DNS name (labels, or a 2-byte 0xC0 compression pointer); returns the position after it. */
    private static Address skipName(Address q)
    {
        while (true)
        {
            int b = q.loadUnsignedByte() & 0xFF;
            if (b == 0)
            {
                return q.plus(1);
            }
            if ((b & 0xC0) == 0xC0)
            {
                return q.plus(2);                            // compression pointer ends the name
            }
            q = q.plus(1 + b);
        }
    }

    /** Send an ARP request for {@code targetIp} and wait (~4 s) for the reply, storing the sender MAC in
     *  {@code macOut}. Returns true on a matching reply. */
    private static boolean arpResolve(Address targetIp, Address macOut)
    {
        Address buf = Heap.allocData(128);
        int flen = buildArp(buf, targetIp);
        Address rx = Heap.allocData(2048);
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;                              // (re)send the request every 0.5 s
        while (Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                txData(buf, flen);
                nextSend = now + freq / 2L;
            }
            int len = waitFrameIrq(rx, 2048, RX_WAIT_MS);
            if (len == 0)
            {
                continue;                                // no delay — drain the FIFO as fast as possible
            }
            if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
            {
                continue;
            }
            Address eth = rx.plus(rx.plus(7).loadUnsignedByte() & 0xFF);
            eth = eth.plus(4 + (eth.plus(3).loadUnsignedByte() & 0xFF) * 4);   // skip BDC
            int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if ((eth.loadUnsignedByte() & 0xFF) != 0xFF)       // DIAG: print only UNICAST frames (skip the bcast flood)
            {
                board.bcm2711.Uart.write(Magic.bytes("  uni et="));
                VM.printHex(et);
                board.bcm2711.Uart.write(Magic.bytes(" dst="));
                printHex2(eth.loadUnsignedByte() & 0xFF);
                printHex2(eth.plus(5).loadUnsignedByte() & 0xFF);
                board.bcm2711.Uart.putc(0x0A);
            }
            if (et != 0x0806)
            {
                continue;                                // not ARP
            }
            Address arp = eth.plus(14);
            if (((arp.plus(6).loadUnsignedByte() & 0xFF) << 8 | (arp.plus(7).loadUnsignedByte() & 0xFF)) != 2)
            {
                continue;                                // not a reply
            }
            if (ipEq(arp.plus(14), targetIp))                // sender protocol addr == target
            {
                int i = 0;
                while (i < 6)
                {
                    macOut.plus(i).store((byte) arp.plus(8 + i).loadUnsignedByte());   // sender hardware addr
                    i = i + 1;
                }
                return true;
            }
        }
        return false;
    }

    /** Build an ARP request (who-has {@code targetIp}) into {@code buf}, padded to 60 bytes; returns 60. */
    private static int buildArp(Address buf, Address targetIp)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) 0xFF);                 // dst = broadcast
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0806 = ARP
        buf.plus(13).store((byte) 0x06);
        Address a = buf.plus(14);
        be16(a.plus(0), 1);                                  // htype = ethernet
        be16(a.plus(2), 0x0800);                             // ptype = IPv4
        a.plus(4).store((byte) 6);                          // hlen
        a.plus(5).store((byte) 4);                          // plen
        be16(a.plus(6), 1);                                  // oper = request
        i = 0;
        while (i < 6)
        {
            a.plus(8 + i).store((byte) ourMac.plus(i).loadUnsignedByte());   // sender hardware addr
            i = i + 1;
        }
        copy4(ourIp, a.plus(14));                            // sender protocol addr
        copy4(targetIp, a.plus(24));                         // target protocol addr (tha stays 0)
        return 60;                                       // 14 + 28, padded to the 60-byte minimum
    }

    /** Send an ICMP echo request to {@code targetIp} (via {@code targetMac}) and wait (~4 s) for the reply. */
    private static boolean ping(Address targetIp, Address targetMac)
    {
        Address buf = Heap.allocData(128);
        txData(buf, buildPing(buf, targetIp, targetMac));
        Address rx = Heap.allocData(2048);
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 4L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            int len = waitFrameIrq(rx, 2048, RX_WAIT_MS);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
            {
                continue;
            }
            Address eth = rx.plus(rx.plus(7).loadUnsignedByte() & 0xFF);
            eth = eth.plus(4 + (eth.plus(3).loadUnsignedByte() & 0xFF) * 4);
            if (((eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF)) != 0x0800)
            {
                continue;
            }
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 1)
            {
                continue;                                // not ICMP
            }
            Address icmp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            if ((icmp.loadUnsignedByte() & 0xFF) != 0)
            {
                continue;                                // not echo reply
            }
            if (ipEq(ip.plus(12), targetIp))                 // from the target
            {
                return true;
            }
        }
        return false;
    }

    /** Build an ICMP echo request (32-byte payload) into {@code buf}; returns the total frame length. */
    private static int buildPing(Address buf, Address targetIp, Address targetMac)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) targetMac.plus(i).loadUnsignedByte());   // dst = gateway
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0800
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address icmp = buf.plus(34);
        icmp.store((byte) 8);                       // type = echo request
        icmp.plus(1).store((byte) 0);                       // code
        be16(icmp.plus(2), 0);                               // checksum (fill below)
        be16(icmp.plus(4), 0x1234);                          // id
        be16(icmp.plus(6), 1);                               // seq
        int icmpLen = 8 + 32;                            // header + 32 zero payload bytes
        be16(icmp.plus(2), ipCksum(icmp, icmpLen));          // ICMP checksum over the whole message
        ip.store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        be16(ip.plus(2), 20 + icmpLen);
        be16(ip.plus(4), 0);
        be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);                        // TTL
        ip.plus(9).store((byte) 1);                         // protocol = ICMP
        be16(ip.plus(10), 0);
        copy4(ourIp, ip.plus(12));
        copy4(targetIp, ip.plus(16));
        be16(ip.plus(10), ipCksum(ip, 20));
        return 14 + 20 + icmpLen;
    }

    /** Compare two 4-byte IPv4 addresses. */
    private static boolean ipEq(Address a, Address b)
    {
        int i = 0;
        while (i < 4)
        {
            if ((a.plus(i).loadUnsignedByte() & 0xFF) != (b.plus(i).loadUnsignedByte() & 0xFF))
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    /**
     * Build a DHCP message into {@code buf}: Ethernet(bcast)/IP/UDP(68→67)/BOOTP + options. {@code msgType}
     * 1=DISCOVER 3=REQUEST. If {@code reqIp}/{@code serverId} are non-zero, adds option 50 (requested IP) /
     * option 54 (server id). Returns the total frame length.
     */
    private static int buildDhcp(Address buf, int msgType, Address reqIp, Address serverId)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) 0xFF);                 // dst = broadcast
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());   // src = ourMac
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0800
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address udp = buf.plus(34);
        Address bootp = buf.plus(42);
        bootp.store((byte) 1);                      // op = BOOTREQUEST
        bootp.plus(1).store((byte) 1);                      // htype
        bootp.plus(2).store((byte) 6);                      // hlen
        bootp.plus(4).store(0x3903F326);            // xid
        be16(bootp.plus(10), 0x8000);                        // broadcast flag
        i = 0;
        while (i < 6)
        {
            bootp.plus(28 + i).store((byte) ourMac.plus(i).loadUnsignedByte());   // chaddr
            i = i + 1;
        }
        bootp.plus(236).store((byte) 0x63);                 // magic cookie
        bootp.plus(237).store((byte) 0x82);
        bootp.plus(238).store((byte) 0x53);
        bootp.plus(239).store((byte) 0x63);
        Address opt = bootp.plus(240);
        int p = 0;
        opt.plus(p).store((byte) 53);                       // DHCP message type
        opt.plus(p + 1).store((byte) 1);
        opt.plus(p + 2).store((byte) msgType);
        p = p + 3;
        if (!reqIp.isZero())
        {
            opt.plus(p).store((byte) 50);                   // requested IP address
            opt.plus(p + 1).store((byte) 4);
            copy4(reqIp, opt.plus(p + 2));
            p = p + 6;
        }
        if (!serverId.isZero())
        {
            opt.plus(p).store((byte) 54);                   // server identifier
            opt.plus(p + 1).store((byte) 4);
            copy4(serverId, opt.plus(p + 2));
            p = p + 6;
        }
        opt.plus(p).store((byte) 55);                       // parameter request list
        opt.plus(p + 1).store((byte) 4);
        opt.plus(p + 2).store((byte) 1);                    //   subnet
        opt.plus(p + 3).store((byte) 3);                    //   router
        opt.plus(p + 4).store((byte) 6);                    //   DNS
        opt.plus(p + 5).store((byte) 15);                   //   domain
        p = p + 6;
        opt.plus(p).store((byte) 255);                      // end
        p = p + 1;
        int dhcpLen = 240 + p;
        be16(udp.plus(0), 68);                               // UDP src port
        be16(udp.plus(2), 67);                               // UDP dst port
        be16(udp.plus(4), 8 + dhcpLen);
        be16(udp.plus(6), 0);                                // no UDP checksum
        ip.store((byte) 0x45);                      // IPv4, IHL=5
        ip.plus(1).store((byte) 0);
        be16(ip.plus(2), 20 + 8 + dhcpLen);
        be16(ip.plus(4), 0);
        be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);                        // TTL
        ip.plus(9).store((byte) 17);                        // UDP
        be16(ip.plus(10), 0);
        i = 0;
        while (i < 4)
        {
            ip.plus(16 + i).store((byte) 0xFF);             // dst = 255.255.255.255
            i = i + 1;
        }
        be16(ip.plus(10), ipCksum(ip, 20));
        return 14 + 20 + 8 + dhcpLen;
    }

    /**
     * Wait (~6 s) for a DHCP reply of message type {@code wantType} (2=Offer, 5=Ack) on UDP port 68. On a
     * match: copies yiaddr → {@code yiOut} and, when the corresponding out is non-zero, options
     * 54→serverIdOut, 1→snOut, 3→gwOut, 6→dnsOut. Returns true if a matching reply arrived.
     */
    private static boolean recvDhcp(Address rx, int wantType, Address yiOut, Address serverIdOut, Address snOut,
            Address gwOut, Address dnsOut)
    {
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 6L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            int len = waitFrameIrq(rx, 2048, RX_WAIT_MS);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            if ((rx.plus(5).loadUnsignedByte() & 0x0F) == 0)
            {
                continue;                                // ioctl ack
            }
            int doff = rx.plus(7).loadUnsignedByte() & 0xFF;
            Address bdc = rx.plus(doff);
            Address eth = bdc.plus(4 + (bdc.plus(3).loadUnsignedByte() & 0xFF) * 4);
            int ethertype = ((eth.plus(12).loadUnsignedByte() & 0xFF) << 8) | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if (ethertype != 0x0800)
            {
                continue;
            }
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 17)
            {
                continue;
            }
            Address udp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            int dport = ((udp.plus(2).loadUnsignedByte() & 0xFF) << 8) | (udp.plus(3).loadUnsignedByte() & 0xFF);
            if (dport != 68)
            {
                continue;
            }
            Address bootp = udp.plus(8);
            Address opt = bootp.plus(240);
            Address optEnd = rx.plus(len);
            if (optFirst(opt, optEnd, 53) != wantType)   // DHCP message type option
            {
                continue;
            }
            copy4(bootp.plus(16), yiOut);                     // yiaddr
            optCopy(opt, optEnd, 54, serverIdOut);
            optCopy(opt, optEnd, 1, snOut);
            optCopy(opt, optEnd, 3, gwOut);
            optCopy(opt, optEnd, 6, dnsOut);
            return true;
        }
        return false;
    }

    /** Return the first value byte of DHCP option {@code code}, or -1 if absent. */
    private static int optFirst(Address opt, Address end, int code)
    {
        Address o = opt;
        while (o.toLong() < end.toLong())
        {
            int c = o.loadUnsignedByte() & 0xFF;
            if (c == 255)
            {
                return -1;
            }
            if (c == 0)
            {
                o = o.plus(1);
                continue;
            }
            int l = o.plus(1).loadUnsignedByte() & 0xFF;
            if (c == code)
            {
                return o.plus(2).loadUnsignedByte() & 0xFF;
            }
            o = o.plus(2 + l);
        }
        return -1;
    }

    /** Copy up to 4 bytes of DHCP option {@code code} to {@code dst} (no-op if dst==0 or option absent). */
    private static void optCopy(Address opt, Address end, int code, Address dst)
    {
        if (dst.isZero())
        {
            return;
        }
        Address o = opt;
        while (o.toLong() < end.toLong())
        {
            int c = o.loadUnsignedByte() & 0xFF;
            if (c == 255)
            {
                return;
            }
            if (c == 0)
            {
                o = o.plus(1);
                continue;
            }
            int l = o.plus(1).loadUnsignedByte() & 0xFF;
            if (c == code)
            {
                int i = 0;
                while (i < 4 && i < l)
                {
                    dst.plus(i).store((byte) o.plus(2 + i).loadUnsignedByte());
                    i = i + 1;
                }
                return;
            }
            o = o.plus(2 + l);
        }
    }

    /** Copy 4 bytes from {@code src} to {@code dst}. */
    private static void copy4(Address src, Address dst)
    {
        int i = 0;
        while (i < 4)
        {
            dst.plus(i).store((byte) src.plus(i).loadUnsignedByte());
            i = i + 1;
        }
    }

    /** One's-complement IPv4 header checksum over {@code len} bytes at {@code addr}. */
    private static int ipCksum(Address addr, int len)
    {
        int sum = 0;
        int i = 0;
        while (i < len)
        {
            sum = sum + (((addr.plus(i).loadUnsignedByte() & 0xFF) << 8) | (addr.plus(i + 1).loadUnsignedByte() & 0xFF));
            i = i + 2;
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Store a 16-bit big-endian (network-order) value. */
    private static void be16(Address addr, int v)
    {
        addr.store((byte) ((v >> 8) & 0xFF));
        addr.plus(1).store((byte) (v & 0xFF));
    }

    /** Print a dotted-quad IPv4 address (4 bytes at addr). */
    private static void printIp(Address addr)
    {
        int i = 0;
        while (i < 4)
        {
            VM.printDec(addr.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 3)
            {
                board.bcm2711.Uart.putc(0x2E);           // '.'
            }
            i = i + 1;
        }
    }

    /** Print a byte as two lowercase hex digits. */
    private static void printHex2(int v)
    {
        board.bcm2711.Uart.putc(hexDigit((v >> 4) & 0x0F));
        board.bcm2711.Uart.putc(hexDigit(v & 0x0F));
    }

    private static int hexDigit(int n)
    {
        return n < 10 ? (0x30 + n) : (0x61 + n - 10);
    }

    /** Send a 4-byte (u32) SET ioctl and log its ack status. */
    private static void setInt(int cmd, int val)
    {
        Address b = Heap.allocData(16);
        b.store(val);
        readCtrl(sendBcdc(cmd, b, 4, true));
    }

    /** Copy the SSID from wifi.conf's first line into {@code dst} (strips an optional "ssid=" prefix and any
     *  trailing CR/LF); returns its length (<=32). */
    private static int parseSsid(Address src, int flen, Address dst)
    {
        int start = 0;
        if (flen >= 5 && (src.loadUnsignedByte() & 0xFF) == 0x73 && (src.plus(1).loadUnsignedByte() & 0xFF) == 0x73
                && (src.plus(2).loadUnsignedByte() & 0xFF) == 0x69 && (src.plus(3).loadUnsignedByte() & 0xFF) == 0x64
                && (src.plus(4).loadUnsignedByte() & 0xFF) == 0x3D)                 // "ssid="
        {
            start = 5;
        }
        int n = 0;
        int i = start;
        while (i < flen && n < 32)
        {
            int c = src.plus(i).loadUnsignedByte() & 0xFF;
            if (c == 0x0A || c == 0x0D || c == 0)
            {
                break;
            }
            dst.plus(n).store((byte) c);
            n = n + 1;
            i = i + 1;
        }
        return n;
    }

    /** Find the BRCM event ethertype 0x886C in a frame; returns its offset, or -1. */
    private static int findEthertype(Address rx, int len)
    {
        int i = 12;
        while (i < 64 && i < len - 2)
        {
            if ((rx.plus(i).loadUnsignedByte() & 0xFF) == 0x88 && (rx.plus(i + 1).loadUnsignedByte() & 0xFF) == 0x6C)
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /**
     * Log join-relevant firmware events and report link-up. event_msg fields are big-endian: flags@+2 (bit0 =
     * WLC_EVENT_MSG_LINK), event_type@+4, status@+8. Returns true on E_LINK(16) with the link flag set.
     */
    private static boolean parseJoinEvent(Address rx, int len)
    {
        int eth = findEthertype(rx, len);
        if (eth < 0)
        {
            return false;
        }
        Address em = rx.plus(eth + 12);                         // event_msg = ethertype(2) + bcmeth_hdr(10)
        int flags = ((em.plus(2).loadUnsignedByte() & 0xFF) << 8) | (em.plus(3).loadUnsignedByte() & 0xFF);
        int et = beU32(em.plus(4));
        int status = beU32(em.plus(8));
        board.bcm2711.Uart.write(Magic.bytes("  event "));   // print EVERY join-phase event to trace the flow
        VM.printDec(et);
        board.bcm2711.Uart.write(Magic.bytes(" status "));
        VM.printDec(status);
        board.bcm2711.Uart.write(Magic.bytes(" flags="));
        VM.printHex(flags);
        board.bcm2711.Uart.putc(0x0A);
        return et == 16 && (flags & 1) != 0;             // E_LINK, link up
    }

    /**
     * Enable just the events we act on (a targeted {@code event_msgs} bitmask, not all-ones) so the join
     * trace isn't buried under RSSI/probe/misc event spam. GET first to learn the mask length. Bits: the
     * association-lifecycle events (set_ssid/join/auth/deauth/assoc/disassoc/link/psk_sup).
     */
    static void enableEvents()
    {
        Address g = Heap.allocData(256);
        int p = putStr(g, Magic.bytes("event_msgs"));
        Address rx = Heap.allocData(256);
        int id = sendBcdc(WLC_GET_VAR, g, p + 64, false);
        int len = recvCtrl(rx, 256, id);
        int mlen = 16;                                   // fallback
        if (len > 0)
        {
            int doff = rx.plus(7).loadUnsignedByte() & 0xFF;
            int returned = rx.plus(doff + 4).loadInt();  // BCDC len = mask length the fw uses
            if (returned > 0 && returned <= 64)
            {
                mlen = returned;
            }
        }
        log(Magic.bytes("evt masklen="), mlen);
        Address s = Heap.allocData(256);
        int q = putStr(s, Magic.bytes("event_msgs"));    // mask is zeroed by allocData
        Address m = s.plus(q);
        setEvt(m, 0);                                    // E_SET_SSID
        setEvt(m, 1);                                    // E_JOIN
        setEvt(m, 3);                                    // E_AUTH
        setEvt(m, 5);                                    // E_DEAUTH
        setEvt(m, 6);                                    // E_DEAUTH_IND
        setEvt(m, 7);                                    // E_ASSOC
        setEvt(m, 11);                                   // E_DISASSOC
        setEvt(m, 12);                                   // E_DISASSOC_IND
        setEvt(m, 16);                                   // E_LINK
        setEvt(m, 46);                                   // E_PSK_SUP (for WPA later)
        readCtrl(sendBcdc(WLC_SET_VAR, s, q + mlen, true));
    }

    /** Set event bit {@code ev} in the event_msgs bitmask (byte ev/8, bit ev%8). */
    private static void setEvt(Address mask, int ev)
    {
        Address b = mask.plus(ev >> 3);
        b.store((byte) ((b.loadUnsignedByte() & 0xFF) | (1 << (ev & 7))));
    }

    /** Print {@code len} bytes at {@code addr} as printable ASCII (non-printable -> '.'), not NUL-terminated. */
    private static void dumpRaw(Address addr, int len)
    {
        int i = 0;
        while (i < len)
        {
            int c = addr.plus(i).loadUnsignedByte() & 0xFF;
            board.bcm2711.Uart.putc((c >= 0x20 && c < 0x7F) ? c : 0x2E);
            i = i + 1;
        }
        board.bcm2711.Uart.putc(0x0A);
    }

    /**
     * Download the CLM blob (regulatory + PHY calibration) the firmware needs before it can scan/join —
     * without it a scan returns BCME_NOTUP. Sent as the chunked {@code clmload} iovar: each chunk
     * is a 12-byte brcmf_dload_data header {flag, dload_type=CLM, len, crc=0} + data, with DL_BEGIN on the
     * first chunk and DL_END on the last (flag also carries the handler version in bits 12+).
     */
    static void clmLoad()
    {
        Address e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.clm_blob"));
        if (e.isZero())
        {
            board.bcm2711.Uart.write(Magic.bytes("  clm NOT FOUND\n"));
            return;
        }
        Address src = e.plus(16L).loadAddress();
        int total = (int) e.plus(24L).loadLong();
        log(Magic.bytes("clm bytes="), total);
        int off = 0;
        while (off < total)
        {
            int n = total - off;
            if (n > 400)
            {
                n = 400;                                 // keep name+header+chunk under the 512-byte TX limit
            }
            int flag = 0x1000;                           // DLOAD_HANDLER_VER (1) << 12
            if (off == 0)
            {
                flag = flag | 0x2;                       // DL_BEGIN
            }
            if (off + n >= total)
            {
                flag = flag | 0x4;                       // DL_END
            }
            Address buf = Heap.allocData(512);
            int p = putStr(buf, Magic.bytes("clmload")); // "clmload\0"
            store16(buf.plus(p), flag);
            store16(buf.plus(p + 2), 2);                     // dload_type = DL_TYPE_CLM
            buf.plus(p + 4).store(n);               // chunk len
            buf.plus(p + 8).store(0);               // crc unused
            int i = 0;
            while (i < n)
            {
                buf.plus(p + 12 + i).store((byte) src.plus(off + i).loadUnsignedByte());
                i = i + 1;
            }
            readCtrl(sendBcdc(WLC_SET_VAR, buf, p + 12 + n, true));
            off = off + n;
        }
        board.bcm2711.Uart.write(Magic.bytes("  clm loaded\n"));
    }

    /** Build + send a BCDC ioctl (SET or GET) with {@code dataLen} bytes at {@code dataAddr} on the control
     *  channel; returns the request id. */
    static int sendBcdc(int cmd, Address dataAddr, int dataLen, boolean isSet)
    {
        Address tx = Heap.allocData(1024);
        int i = 0;
        while (i < dataLen)
        {
            tx.plus(28 + i).store((byte) dataAddr.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        tx.plus(12).store(cmd);
        tx.plus(16).store(dataLen);
        tx.plus(20).store((reqId << 16) | (isSet ? 0x02 : 0));   // flags: id | SET
        tx.plus(24).store(0);
        int frameLen = 12 + 16 + dataLen;
        tx.plus(5).store((byte) 0);                         // control channel
        tx.plus(6).store((byte) 0);
        tx.plus(7).store((byte) 12);
        store16(tx.plus(0), frameLen);
        store16(tx.plus(2), ~frameLen);
        sendFrame(tx, frameLen);
        int id = reqId;
        reqId = reqId + 1;
        return id;
    }

    /**
     * The next received frame for a consumer: from its queue {@code q} once the RX task owns the chip, else
     * straight off the chip (bring-up, where this task is the only reader). 0 when nothing came within
     * {@link #RX_WAIT_MS}.
     */
    static int rxFrame(int q, Address dst, int cap)
    {
        if (net.RxQueues.active)
        {
            if (q < 0)
            {
                vm.VMScheduler.kernelSleep(RX_WAIT_MS);  // no queue could be bound (table full): nothing
                return 0;                                //   can arrive for this consumer
            }
            return net.RxQueues.recv(q, dst, cap, RX_WAIT_MS);
        }
        return waitFrameIrq(dst, cap, RX_WAIT_MS);
    }

    /** Read the ioctl ack matching request {@code id} and log its status word (0 = OK). */
    static void readCtrl(int id)
    {
        Address rx = Heap.allocData(1024);
        int len = recvCtrl(rx, 1024, id);
        if (len == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  (no ctrl resp)\n"));
            return;
        }
        int doff = rx.plus(7).loadUnsignedByte() & 0xFF;
        log(Magic.bytes("ctrl status="), rx.plus(doff + 12).loadInt());
    }

    /**
     * Receive the control-channel BCDC response for request {@code wantId} into {@code dst}, skipping the
     * event/credit frames the firmware interleaves on other channels (they are logged). Returns the frame
     * length, or 0 on timeout. This is the RX dispatch: an ioctl ack is a channel-0 frame whose BCDC id
     * (flags bits 16..31) echoes the id we sent — anything else is an async event, not our answer.
     */
    static int recvCtrl(Address dst, int cap, int wantId)
    {
        return recvCtrl(dst, cap, wantId, 1500);         // ~1.5 s (was 600 * 2 ms poll)
    }

    /** {@link #recvCtrl}, waiting up to {@code ms}. */
    static int recvCtrl(Address dst, int cap, int wantId, int ms)
    {
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * ms / 1000L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            int len = rxFrame(net.RxQueues.Q_CTRL, dst, cap);
            if (len == 0)
            {
                continue;
            }
            int ch = dst.plus(5).loadUnsignedByte() & 0x0F;
            if (ch != 0)                                 // event (1) / data (2) — not an ioctl ack
            {
                if (!net.RxQueues.active)
                {
                    holdIfEapol(dst, len);               // bring-up: this task is the only reader
                }
                log(Magic.bytes("  (skip ch="), ch);
                continue;
            }
            int doff = dst.plus(7).loadUnsignedByte() & 0xFF;
            int id = (dst.plus(doff + 8).loadInt() >> 16) & 0xFFFF;   // BCDC flags: id in bits 16..31
            if (wantId != 0 && id != wantId)
            {
                log(Magic.bytes("  (skip id="), id);
                continue;
            }
            return len;
        }
        return 0;
    }

    /**
     * Read exactly one SDPCM frame: the 4-byte hw header (gives len/~len), then exactly len-4 body bytes —
     * so we never over-read into the next frame (the bug that corrupted the escan stream). Returns the frame
     * length, or 0 if this attempt saw no valid frame.
     */
    static int readFrameOnce(Address dst, int cap)
    {
        if (!Sdio.cmd53Read(F2, 0, true, dst.toLong(), 1, 4))     // hw header
        {
            return 0;
        }
        int hw = dst.loadInt();
        int len = hw & 0xFFFF;
        int nlen = (hw >> 16) & 0xFFFF;
        if (len < 12 || ((len ^ nlen) & 0xFFFF) != 0xFFFF)
        {
            return 0;                                    // no frame / invalid header
        }
        int body = len - 4;
        int rest = 0;
        if (body > cap - 4)
        {
            rest = body - (cap - 4);                     // the part that does not fit: drained below
            body = cap - 4;                              // clamp to the buffer (event frames can be large)
        }
        int done = 0;
        while (done < body)                              // read the body in <=512-byte CMD53 bursts
        {
            int n = body - done;
            if (n > 512)
            {
                n = 512;
            }
            if (!Sdio.cmd53Read(F2, 0, true, dst.plus(4 + done).toLong(), 1, n))
            {
                return 0;
            }
            done = done + n;
        }
        txMax = dst.plus(9).loadUnsignedByte() & 0xFF;             // SDPCM sw header: the firmware's tx window (max seq)
        txFc = dst.plus(8).loadUnsignedByte() & 0xFF;              // SDPCM sw header: the flow-control mask
        if (rest > 0)
        {
            // An oversize frame must be read to its end: what is left in the F2 FIFO would otherwise be parsed
            // as the next frame's header -- garbage, so every later read looks "empty" until the stream
            // happens to realign, while real frames queue up behind it in the chip.
            if (drainBuf.isZero())
            {
                drainBuf = vm.Heap.allocData(512);
            }
            while (rest > 0)
            {
                int n = rest > 512 ? 512 : rest;
                if (!Sdio.cmd53Read(F2, 0, true, drainBuf.toLong(), 1, n))
                {
                    break;
                }
                rest = rest - n;
            }
            return cap;
        }
        return len;
    }

    private static Address drainBuf;                     // where the unread rest of an oversize frame goes

    // RX glom (superframe). The firmware may bundle several frames into one F2 transfer: first a DESCRIPTOR
    // (channel 3 with flag 0x80; its payload is one little-endian u16 length per subframe), then the
    // SUPERFRAME itself (channel 3, no flag): a 12-byte superframe header padded to its data offset, then the
    // subframes, each a whole SDPCM frame (own hw + sw header, channel 1 or 2) at the start of its descriptor
    // length -- the first one after the superframe header. Dropping channel 3 (Pi run 18: glom=6, connect
    // timed out with no SYN-ACK) discards every frame inside. Layout per brcmfmac's brcmf_sdio_rxglom.
    private static final int GLOM_MAX = 32768;          // largest superframe kept (bigger ones are drained)
    private static final int GLOM_SUBS = 64;             // most subframes in one descriptor
    public static Address glomBuf;                       // the last superframe read
    public static int[] glomLens = new int[GLOM_SUBS];   // its descriptor lengths
    public static int glomCount;                         // how many of glomLens are valid
    private static int glomIdx;                          // the next descriptor entry glomNext hands out
    private static int glomOff;                          // where that entry's chunk starts in glomBuf
    static int bringupGlom;                              // superframes waitFrameIrq unpacked (before the RX task)
    static int bringupGlomSubs;                          // ... and their descriptor entries

    /** True if the frame at {@code dst} is a glom descriptor (channel 3, descriptor flag set). */
    public static boolean isGlomDesc(Address dst)
    {
        return (dst.plus(5).loadUnsignedByte() & 0x8F) == 0x83;
    }

    /**
     * Read the superframe that follows the glom descriptor at {@code desc} ({@code len} bytes) into
     * {@link #glomBuf}, with its subframe lengths in {@link #glomLens}. The caller holds the bus. Returns the
     * superframe length, or 0 if it was malformed or too big (it has still been read out of the FIFO).
     */
    public static int readSuperframe(Address desc, int len)
    {
        int doff = desc.plus(7).loadUnsignedByte() & 0xFF;
        int total = 0;
        int count = 0;
        int p = doff;
        while (p + 2 <= len)
        {
            int sub = (desc.plus(p).loadUnsignedByte() & 0xFF) | (desc.plus(p + 1).loadUnsignedByte() & 0xFF) << 8;
            if (count < GLOM_SUBS)
            {
                glomLens[count] = sub;
            }
            count = count + 1;
            total = total + sub;
            p = p + 2;
        }
        glomCount = 0;                                   // nothing pending until it is read and checked
        glomIdx = 0;
        glomOff = 0;
        if (glomBuf.isZero())
        {
            glomBuf = vm.Heap.allocData(GLOM_MAX);
        }
        if (drainBuf.isZero())
        {
            drainBuf = vm.Heap.allocData(512);
        }
        int keep = total > GLOM_MAX ? 0 : total;
        int done = 0;
        while (done < total)                             // read it all, so the FIFO stays aligned
        {
            int n = total - done;
            if (n > 512)
            {
                n = 512;
            }
            Address to = done < keep ? glomBuf.plus(done) : drainBuf;
            if (!Sdio.cmd53Read(F2, 0, true, to.toLong(), 1, n))
            {
                return 0;
            }
            done = done + n;
        }
        if (keep == 0 || count > GLOM_SUBS || total < 12)
        {
            return 0;
        }
        Address glom = glomBuf;
        int hw = glom.loadInt();
        int hlen = hw & 0xFFFF;
        int nlen = (hw >> 16) & 0xFFFF;
        if (hlen < 12 || ((hlen ^ nlen) & 0xFFFF) != 0xFFFF || (glom.plus(5).loadUnsignedByte() & 0x8F) != 0x03)
        {
            return 0;
        }
        txMax = glom.plus(9).loadUnsignedByte() & 0xFF;         // the superframe header carries the tx window too
        txFc = glom.plus(8).loadUnsignedByte() & 0xFF;          //   and the flow-control mask
        glomCount = count;
        return total;
    }

    /** True while the superframe in {@link #glomBuf} has descriptor entries {@link #glomNext} has not handed out. */
    public static boolean glomPending()
    {
        return glomIdx < glomCount;
    }

    /**
     * Copy the next well-formed subframe of the superframe in {@link #glomBuf} to {@code dst} and return its
     * length, or 0 when none is left. Like {@link #readFrameOnce}, a subframe longer than {@code cap} is clamped
     * and {@code cap} returned. Malformed ones (a bad len/~len, or longer than their descriptor entry) are
     * dropped. {@code dst} 0 drops the next well-formed one instead of copying it.
     */
    public static int glomNext(Address dst, int cap)
    {
        while (glomIdx < glomCount)
        {
            int start = glomOff;
            if (glomIdx == 0)
            {
                start = start + (glomBuf.plus(7).loadUnsignedByte() & 0xFF);   // the first follows the superframe header
            }
            int end = glomOff + glomLens[glomIdx];
            glomOff = end;
            glomIdx = glomIdx + 1;
            if (start + 12 > end)
            {
                continue;
            }
            int hw = glomBuf.plus(start).loadInt();
            int sub = hw & 0xFFFF;
            int nsub = (hw >> 16) & 0xFFFF;
            if (sub < 12 || ((sub ^ nsub) & 0xFFFF) != 0xFFFF || start + sub > end)
            {
                continue;
            }
            if (dst.isZero())
            {
                return 0;
            }
            int n = sub > cap ? cap : sub;
            int k = 0;
            while (k < n)
            {
                dst.plus(k).store((byte) glomBuf.plus(start + k).loadUnsignedByte());
                k = k + 1;
            }
            return n;
        }
        return 0;
    }

    /**
     * The firmware's transmit window: the highest SDPCM sequence number it will accept, from the last frame it
     * sent us. A frame sent beyond it is dropped by the firmware (brcmfmac's txctl / data_ok check), so a
     * transmit waits -- briefly, see {@link #waitCredit} -- for the window to open.
     */
    private static int txMax = 4;

    /** True when {@code txSeq} is inside the window (brcmfmac: (u8)(max - seq) nonzero and below 0x80). */
    private static boolean haveCredit()
    {
        int d = (txMax - txSeq) & 0xFF;
        return d != 0 && (d & 0x80) == 0;
    }

    /**
     * The firmware's flow-control mask from the last SDPCM header: bit {@code p} set means "stop sending
     * precedence {@code p}". brcmfmac keeps it in {@code bus->flowcontrol} and dequeues only the precedences it
     * leaves open. Seen on the Pi as 0xFF for ~12 ms around a group rekey.
     */
    private static int txFc = 0;

    /** Device-level flow control from {@code I_HMB_FC_STATE}: while true no data frame goes (see clearFrameInts). */
    private static boolean txFcState = false;

    /**
     * Act on the transmit-side bits of an SDIOD interrupt status word before it is cleared: a mailbox post is
     * read and ACKed ({@link #hostMail}), and a flow-control change takes the new {@code I_HMB_FC_STATE}. Both
     * the bring-up wait ({@link #waitFrameIrq}) and the RX task ({@link #clearFrameInts}) clear the status, so
     * both must do this first: a bit cleared unread is a mail never ACKed -- the firmware-ready mail at
     * bring-up was exactly that until 2026-11-08.
     */
    private static void serviceInts(int ints)
    {
        if ((ints & SD_MAILBOXINT) != 0)
        {
            hostMail();
        }
        if ((ints & SD_FCCHANGE) != 0)
        {
            txFcState = (ints & SD_FCSTATE) != 0;
        }
    }

    /** Read the firmware's mailbox word, ACK it, and take the flow-control mask it may carry. Bus held. */
    private static void hostMail()
    {
        int data = bpRead32(SDIOD_CORE + SD_TOHOSTMAILBOXDATA);
        bpWrite32(SDIOD_CORE + SD_TOSBMAILBOX, SMB_INT_ACK);
        if ((data & HMB_DATA_FC) != 0)
        {
            txFc = (data >>> 24) & 0xFF;
        }
    }

    /**
     * True when frame {@code tx} may go on the bus now: inside the window, and -- for a data frame (channel 2)
     * -- with its precedence not flow-controlled. A control frame (channel 0) needs only credit, as in brcmfmac.
     */
    private static boolean maySend(Address tx)
    {
        if (!haveCredit())
        {
            return false;
        }
        if ((tx.plus(5).loadUnsignedByte() & 0x0F) != 2)
        {
            return true;
        }
        if (txFcState)
        {
            return false;
        }
        int prio = tx.plus(13).loadUnsignedByte() & 0x07;          // BDC priority (802.1D)
        int prec = (prio == 0 || prio == 3) ? (prio ^ 2) : prio;   // brcmf_sdio_prio2prec
        return ((txFc >> prec) & 1) == 0;
    }

    /**
     * Wait up to 20 ms until frame {@code tx} may be sent. Once the RX task runs it reads the frames that move
     * the window, so yielding is enough; before that nobody reads, and the wait just runs out.
     */
    private static void waitCredit(Address tx)
    {
        if (maySend(tx))
        {
            return;
        }
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 50L;
        while (!maySend(tx))
        {
            if (Magic.readCNTPCT_EL0() >= endT)
            {
                return;
            }
            if (net.RxQueues.active)
            {
                vm.VMScheduler.kernelYield();
            }
        }
    }

    /**
     * Service and clear the SDIOD interrupt status, then the SDHCI card interrupt (de-asserting DAT1). Transmit
     * is interrupt-driven too, and its two signals were being cleared unread:
     * <ul>
     * <li>{@code I_HMB_FC_CHANGE} (bit 5): the device-level flow control {@code I_HMB_FC_STATE} (bit 4) changed.
     *     While it is set no data frame may go (brcmfmac's {@code fcstate}).</li>
     * <li>{@code I_HMB_HOST_INT} (bit 7): the firmware posted {@code tohostmailboxdata}. It must be ACKed with
     *     {@code SMB_INT_ACK} in {@code tosbmailbox}; {@code HMB_DATA_FC} in it carries the per-precedence
     *     flow-control mask in bits 24..31 ({@code brcmf_sdio_hostmail}).</li>
     * </ul>
     * The RX task calls this when the chip is empty, and drains the transmit queue right after.
     */
    static void clearFrameInts()
    {
        int ints = bpRead32(SDIOD_CORE + SD_INTSTATUS);
        serviceInts(ints);
        bpWrite32(SDIOD_CORE + SD_INTSTATUS, ints & ~SD_FCSTATE);   // write-1-to-clear (FC_STATE is a level)
        Sdio.maskCardInt();                              // CARD_INT is not write-1-to-clear (Pi run 6: it stayed
        Sdio.clearCardInt();                             //   0x100 with SD_INTSTATUS and CCCR INT_PENDING both 0);
        Sdio.unmaskCardInt();                            //   toggling its Status Enable is what drops it (SDHCI)
    }

    /** Copy the bytes of {@code s} to {@code dst} then a NUL; returns the length written (incl. NUL). */
    private static int putStr(Address dst, byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            dst.plus(i).store((byte) s[i]);
            i = i + 1;
        }
        dst.plus(s.length).store((byte) 0);
        return s.length + 1;
    }

    /** Store a 16-bit little-endian value (no Magic.store16). */
    private static void store16(Address addr, int v)
    {
        addr.store((byte) (v & 0xFF));
        addr.plus(1).store((byte) ((v >> 8) & 0xFF));
    }

    /** Print up to {@code len} printable bytes at {@code addr} (NUL-terminated) to the UART. */
    private static void dumpAscii(Address addr, int len)
    {
        int i = 0;
        while (i < len)
        {
            int c = addr.plus(i).loadUnsignedByte() & 0xFF;
            if (c == 0)
            {
                break;
            }
            board.bcm2711.Uart.putc((c >= 0x20 && c < 0x7F) ? c : 0x2E);
            i = i + 1;
        }
        board.bcm2711.Uart.putc(0x0A);
    }

    /** Bring the ARM CR4 OUT of reset with the CPU RUNNING (resetcore, CPUHALT cleared) — boots the firmware. */
    private static void armCr4Run(long wrap)
    {
        // coredisable(prereset=CPUHALT, reset=0)
        bpWrite32(wrap + AI_IOCTRL, IOCTL_CPUHALT | IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        bpWrite32(wrap + AI_RESETCTRL, 1);
        bpRead32(wrap + AI_RESETCTRL);
        VM.delayUs(10);
        bpWrite32(wrap + AI_IOCTRL, IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        // resetcore: out of reset, CPU running (postreset=0 -> CPUHALT cleared)
        bpWrite32(wrap + AI_IOCTRL, IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        bpWrite32(wrap + AI_RESETCTRL, 0);
        bpRead32(wrap + AI_RESETCTRL);
        VM.delayUs(10);
        bpWrite32(wrap + AI_IOCTRL, IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        VM.delayUs(10);
    }

    /**
     * Write {@code byteLen} bytes from heap address {@code src} to backplane address {@code bpAddr} via
     * 32-bit CMD53 block transfers, re-windowing at each 32 KB SB boundary. Transfers ≤512-byte byte-mode
     * chunks that never cross a window boundary (RAMBASE is 32 KB-aligned, 512 divides 32 KB). Rounds the
     * final chunk up to a 4-byte word (a few trailing image bytes are harmless — the chip has spare RAM).
     */
    static boolean bpWrite(long bpAddr, Address src, long byteLen)
    {
        long n = (byteLen + 3) & ~3L;                    // whole 32-bit words (a few trailing bytes harmless)
        long off = 0;
        while (off < n)
        {
            if (!bpWrite32(bpAddr + off, src.plus(off).loadInt()))   // proven 4-byte path (setWindow cached)
            {
                board.bcm2711.Uart.write(Magic.bytes("  bpWrite fail @off="));
                VM.printHex(off);
                board.bcm2711.Uart.putc(0x0A);
                return false;
            }
            off = off + 4;
        }
        return true;
    }

    // Decoded from the EROM: the ARM CR4 core's wrapper (AI reset/ioctl control) and candidate RAM bases.
    private static final long ARMCR4_WRAP = 0x18102000L;
    private static final long AI_IOCTRL   = 0x408;       // wrapper: bit0 clock-enable, bit1 force-gated-clock
    private static final long AI_RESETCTRL = 0x800;      // wrapper: bit0 = core in reset
    private static final int IOCTL_CLK = 0x1, IOCTL_FGC = 0x2, IOCTL_CPUHALT = 0x20;

    /**
     * Prepare the ARM CR4 for firmware download the way brcmf_chip_disable_arm(CR4) does: NOT a plain
     * coredisable (which holds the core in reset and turns the TCM OFF — that gave all-zero RAM reads), but
     * a resetcore with the CPU HALT bit — bring the core OUT of reset ({@code RESETCTRL=0}) with
     * {@code IOCTL=CPUHALT|CLK}. The CPU is halted (won't run garbage) but the core + TCM are clocked and
     * out of reset, so the TCM is backplane-accessible for the download. Sequence = coredisable(reset=HALT)
     * then bring out of reset (the ai_resetcore dance).
     */
    private static void armCr4Prepare(long wrap)
    {
        // coredisable(prereset=0, reset=CPUHALT)
        bpWrite32(wrap + AI_IOCTRL, IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        bpWrite32(wrap + AI_RESETCTRL, 1);
        bpRead32(wrap + AI_RESETCTRL);
        VM.delayUs(10);
        bpWrite32(wrap + AI_IOCTRL, IOCTL_CPUHALT | IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        // resetcore: bring OUT of reset with CPU halted
        bpWrite32(wrap + AI_IOCTRL, IOCTL_CPUHALT | IOCTL_FGC | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        bpWrite32(wrap + AI_RESETCTRL, 0);              // DEASSERT reset — this is what turns the TCM on
        bpRead32(wrap + AI_RESETCTRL);
        VM.delayUs(10);
        bpWrite32(wrap + AI_IOCTRL, IOCTL_CPUHALT | IOCTL_CLK);
        bpRead32(wrap + AI_IOCTRL);
        VM.delayUs(10);
    }

    /**
     * M1b-2a: disable the ARM CR4 with its clock forced on, then write a test word to each candidate backplane
     * RAM address and read it back — the one(s) that round-trip 0xDEADBEEF are writable chip RAM, pinning the
     * firmware load address (and proving the backplane block-write path) before the 609 KB upload.
     */
    private static final long ARMCR4_CORE = 0x18002000L;  // ARM CR4 core registers (EROM slave port 0)

    static void ramTest()
    {
        armCr4Prepare(ARMCR4_WRAP);                      // out of reset + CPU halted -> TCM accessible
        board.bcm2711.Uart.write(Magic.bytes("  armcr4 resetctrl="));
        VM.printHex((long) (bpRead32(ARMCR4_WRAP + AI_RESETCTRL) & 0xFFFFFFFFL));
        board.bcm2711.Uart.write(Magic.bytes(" ioctrl="));
        VM.printHex((long) (bpRead32(ARMCR4_WRAP + AI_IOCTRL) & 0xFFFFFFFFL));
        board.bcm2711.Uart.putc(0x0A);

        // ARM CR4 core registers (always accessible — confirm the core base + TCM bank config).
        log(Magic.bytes("cr4 cap[0x00]="), bpRead32(ARMCR4_CORE + 0x00));
        log(Magic.bytes("cr4 cap[0x04]="), bpRead32(ARMCR4_CORE + 0x04));
        log(Magic.bytes("cr4 cap[0x08]="), bpRead32(ARMCR4_CORE + 0x08));

        testAddr(0x00000000L);
        testAddr(0x00180000L);
        testAddr(0x00198000L);
        testAddr(0x00200000L);
    }

    private static void testAddr(long addr)
    {
        int orig = bpRead32(addr);
        bpWrite32(addr, 0xDEADBEEF);
        int rb = bpRead32(addr);
        bpWrite32(addr, orig);                           // restore
        board.bcm2711.Uart.write(Magic.bytes("  ram["));
        VM.printHex(addr);
        board.bcm2711.Uart.write(Magic.bytes("] wrote DEADBEEF read "));
        VM.printHex((long) (rb & 0xFFFFFFFFL));
        board.bcm2711.Uart.putc(0x0A);
    }

    /**
     * M1b-1: dump the chip's EROM (enumeration ROM) — the list of on-chip cores and their base addresses —
     * so the firmware-upload step (M1b-2) can use the ARM core / RAM core addresses read from THIS silicon
     * rather than guessed BCM4345 constants. Reads only (backplane), so low-risk. The AI/DMP descriptor
     * format is decoded offline from this dump. Stops at the end-of-table descriptor (0x0000000F) or 96 words.
     */
    static void dumpErom()
    {
        int eromptr = bpRead32(SI_ENUM_BASE + 0xFC);     // ChipCommon EROMPTR
        board.bcm2711.Uart.write(Magic.bytes("  eromptr="));
        VM.printHex((long) (eromptr & 0xFFFFFFFFL));
        board.bcm2711.Uart.putc(0x0A);
        long erom = eromptr & 0xFFFFFFFFL;
        int i = 0;
        while (i < 96)
        {
            int w = bpRead32(erom + i * 4L);
            board.bcm2711.Uart.write(Magic.bytes("  erom["));
            VM.printDec(i);
            board.bcm2711.Uart.write(Magic.bytes("]="));
            VM.printHex((long) (w & 0xFFFFFFFFL));
            board.bcm2711.Uart.putc(0x0A);
            if (w == 0x0000000F)                         // DMP_DESC_EOT
            {
                break;
            }
            i = i + 1;
        }
    }

    // ----- backplane (F1) 32-bit access through the SB window ---------------------------------------------

    /** Point the F1 backplane window at the 32 KB region containing {@code addr}. */
    private static void setWindow(long addr)
    {
        long win = addr & ~((long) SB_WIN_MASK);
        if (win == curWindow)
        {
            return;
        }
        Sdio.cmd52Write(F1, SBADDR_LOW,  (int) ((win >> 8) & 0xFF));
        Sdio.cmd52Write(F1, SBADDR_MID,  (int) ((win >> 16) & 0xFF));
        Sdio.cmd52Write(F1, SBADDR_HIGH, (int) ((win >> 24) & 0xFF));
        curWindow = win;
    }

    /** Read a 32-bit word at backplane address {@code addr} (little-endian, via CMD53 byte mode on F1). */
    static int bpRead32(long addr)
    {
        setWindow(addr);
        int off = (int) (addr & SB_WIN_MASK) | SB_ACCESS_4B;
        if (!Sdio.cmd53Read(F1, off, true, buf.toLong(), 1, 4))
        {
            return 0;
        }
        return buf.loadInt();
    }

    /** Write a 32-bit word at backplane address {@code addr}. */
    static boolean bpWrite32(long addr, int value)
    {
        setWindow(addr);
        int off = (int) (addr & SB_WIN_MASK) | SB_ACCESS_4B;
        buf.store(value);
        return Sdio.cmd53Write(F1, off, true, buf.toLong(), 1, 4);
    }

    /** UART log: "  &lt;label&gt;0xHEX\n" (values are registers, so hex is the useful form). {@code label}
     *  must be a {@code Magic.bytes("...")} literal — the BYTES intrinsic interns a String LITERAL only. */
    private static void log(byte[] label, int value)
    {
        board.bcm2711.Uart.write(Magic.bytes("  "));
        board.bcm2711.Uart.write(label);
        VM.printHex((long) (value & 0xFFFFFFFFL));      // printHex already emits the "0x" prefix
        board.bcm2711.Uart.putc(0x0A);
    }

    public static int rxEvents;                          // firmware event frames (SDPCM channel 1)

    /**
     * Print a firmware event the RX task received after bring-up, when it is about the link (E_DEAUTH 5,
     * E_DEAUTH_IND 6, E_DISASSOC 11, E_DISASSOC_IND 12, E_LINK 16); count every event. {@code rx} is the
     * SDPCM frame.
     */
    public static void logRxEvent(Address rx, int len)
    {
        rxEvents = rxEvents + 1;
        int eth = findEthertype(rx, len);
        if (eth < 0)
        {
            return;
        }
        Address em = rx.plus(eth + 12);
        int flags = ((em.plus(2).loadUnsignedByte() & 0xFF) << 8) | (em.plus(3).loadUnsignedByte() & 0xFF);
        int et = beU32(em.plus(4));
        if (et != 5 && et != 6 && et != 11 && et != 12 && et != 16)
        {
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: rx event "));
        VM.printDec(et);
        board.bcm2711.Uart.write(Magic.bytes(" status "));
        VM.printDec(beU32(em.plus(8)));
        board.bcm2711.Uart.write(Magic.bytes(" reason "));
        VM.printDec(beU32(em.plus(12)));
        board.bcm2711.Uart.write(Magic.bytes(" flags="));
        VM.printHex(flags);
        board.bcm2711.Uart.write(Magic.bytes(" events="));
        VM.printDec(rxEvents);
        board.bcm2711.Uart.putc(0x0A);
    }
}
