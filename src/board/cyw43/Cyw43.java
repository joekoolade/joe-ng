package board.cyw43;

import board.bcm2711.Sdio;
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

    private static long buf;            // a small heap buffer for CMD53 word transfers
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
        long e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.bin"));
        if (e == 0L)
        {
            board.bcm2711.Uart.write(Magic.bytes("  fw NOT FOUND in ramfs\n"));
            return 0;
        }
        long src = Magic.load64(e + 16L);                // firmware bytes (baked into the image)
        long len = Magic.load64(e + 24L);
        log(Magic.bytes("fw bytes="), (int) len);

        // The bus is still at the ~400 kHz ID clock; raise it so a 609 KB upload isn't glacial.
        Sdio.setClock(25000000);
        log(Magic.bytes("sdio clk ctl="), Sdio.control1());

        int first = Magic.load32(src);                   // the ARM reset vector (first word)
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
        long e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.raspberrypi,4-model-b.txt"));
        if (e == 0L)
        {
            board.bcm2711.Uart.write(Magic.bytes("  nvram NOT FOUND\n"));
            return false;
        }
        long src = Magic.load64(e + 16L);
        int srcLen = (int) Magic.load64(e + 24L);
        long buf = Heap.allocData(8192);
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
    private static int processNvram(long src, int srcLen, long dst)
    {
        int di = 0;
        int i = 0;
        while (i < srcLen)
        {
            int ls = i;
            while (i < srcLen && (Magic.load8(src + i) & 0xFF) != 0x0A)   // to end of line
            {
                i = i + 1;
            }
            int le = i;
            if (i < srcLen) { i = i + 1; }                               // skip '\n'
            if (le > ls && (Magic.load8(src + le - 1) & 0xFF) == 0x0D) { le = le - 1; }   // trim '\r'
            int cs = ls;
            while (cs < le && ((Magic.load8(src + cs) & 0xFF) == 0x20 || (Magic.load8(src + cs) & 0xFF) == 0x09))
            {
                cs = cs + 1;                                             // skip leading whitespace
            }
            if (cs >= le || (Magic.load8(src + cs) & 0xFF) == 0x23)      // blank or '#' comment
            {
                continue;
            }
            int j = cs;
            while (j < le)
            {
                Magic.store8(dst + di, Magic.load8(src + j));
                di = di + 1;
                j = j + 1;
            }
            Magic.store8(dst + di, 0);                                   // NUL terminate each entry
            di = di + 1;
        }
        while ((di & 3) != 0)                                           // pad to 4 bytes
        {
            Magic.store8(dst + di, 0);
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
        readFirstFrame();                                // M1c-1: dump the firmware's first SDPCM frame
    }

    /**
     * M1c-1: enable SDIO interrupts, wait for the firmware's first SDPCM frame (it announces bus credits /
     * an initial event after boot), and dump the raw SDPCM header — the ground truth for the framing the TX
     * and BCDC layers need. hw header = [len:16][~len:16]; sw header = seq/channel/nextlen/dataoff/flow/maxseq.
     */
    static void readFirstFrame()
    {
        Sdio.cmd52Write(F0, CCCR_IEN, 0x07);             // enable interrupts: master + F1 + F2
        int tries = 0;
        while ((Sdio.interrupt() & SDHCI_CARD_INT) == 0 && tries < 1000)
        {
            tries = tries + 1;
            VM.delayMs(1);
        }
        log(Magic.bytes("sdhci int="), Sdio.interrupt());
        long rb = Heap.allocData(256);
        if (!Sdio.cmd53Read(F2, 0, true, rb, 1, 64))     // read the first 64 bytes of the F2 stream (byte mode)
        {
            board.bcm2711.Uart.write(Magic.bytes("  F2 read FAILED\n"));
            return;
        }
        int hw = Magic.load32(rb);                       // [len:16][~len:16]
        int sw = Magic.load32(rb + 4);                   // seq(8) | channel+flags(8) | nextlen(8) | dataoff(8)
        int sw2 = Magic.load32(rb + 8);                  // flow(8) | maxseq(8) | reserved(16)
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
        long tx = Heap.allocData(512);                   // zeroed
        int dataLen = 192;                               // response buffer for the version string
        Magic.store8(tx + 28, 0x76);                     // "ver\0" (BCDC data, right after the 16-byte BCDC hdr)
        Magic.store8(tx + 29, 0x65);
        Magic.store8(tx + 30, 0x72);
        Magic.store8(tx + 31, 0);
        Magic.store32(tx + 12, WLC_GET_VAR);             // BCDC cmd
        Magic.store32(tx + 16, dataLen);                 // BCDC len (buffer size the fw fills)
        Magic.store32(tx + 20, 1 << 16);                 // BCDC flags: id=1 (high 16), GET (no SET bit)
        Magic.store32(tx + 24, 0);                       // BCDC status
        int frameLen = 12 + 16 + dataLen;                // 220
        Magic.store8(tx + 4, txSeq & 0xFF);              // SDPCM sw: seq
        Magic.store8(tx + 5, 0);                         //   channel 0 = control
        Magic.store8(tx + 6, 0);                         //   nextlen
        Magic.store8(tx + 7, 12);                        //   dataoffset = 12 (payload after the 12-byte hdr)
        store16(tx + 0, frameLen);                       // SDPCM hw: len
        store16(tx + 2, ~frameLen);                      //   ~len
        if (!Sdio.cmd53Write(F2, 0, true, tx, 1, (frameLen + 3) & ~3))
        {
            board.bcm2711.Uart.write(Magic.bytes("  ioctl tx FAILED\n"));
            return;
        }
        txSeq = txSeq + 1;
        reqId = reqId + 1;                               // ver consumed id 1; scan ioctls get 2,3,...

        long rx = Heap.allocData(1024);
        int rlen = recvCtrl(rx, 1024, 1);                // ver uses BCDC id 1
        if (rlen == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  no ioctl response\n"));
            return;
        }
        int doff = Magic.load8(rx + 7) & 0xFF;           // response data offset
        log(Magic.bytes("resp len="), rlen);
        log(Magic.bytes("resp doff="), doff);
        board.bcm2711.Uart.write(Magic.bytes("wifi: ver = "));
        dumpAscii(rx + doff + 16, 180);                  // value = after sdpcm(doff) + bcdc(16)

        scanOnly();                                      // M2: bring the interface up + escan + dump results
    }

    private static int reqId = 1;                        // BCDC request id (echoed in the response)
    private static final int WLC_UP = 2;                 // ioctl: bring the interface up
    private static final int WLC_SCAN = 50;              // ioctl: start a scan
    private static final int WLC_SCAN_RESULTS = 51;      // ioctl: read the scan result list (GET)
    private static final int WLC_SET_VAR = 263;          // ioctl: set a named iovar

    /**
     * M2 (scan-only): bring the interface up, run a synchronous scan (WLC_SCAN, wait, WLC_SCAN_RESULTS), and
     * dump the result buffer's ASCII so the visible SSIDs show up — no event_msgs / escan dependency, just
     * the proven GET path. Proves the radio hears networks before we attempt a join.
     */
    static void scanOnly()
    {
        board.bcm2711.Uart.write(Magic.bytes("wifi: scan...\n"));
        clmLoad();                                       // regulatory/PHY data — the radio needs it to scan
        enableEvents();                                  // turn on E_ESCAN_RESULT so results are pushed to us
        readCtrl(sendBcdc(WLC_UP, 0L, 0, true));         // bring the interface up
        VM.delayMs(100);

        // This is fullmac firmware: scan via the escan iovar, results arrive as E_ESCAN_RESULT events (not
        // WLC_SCAN_RESULTS, which it rejects). escan params = 8-byte header + wl_scan_params_t (broadcast).
        long es = Heap.allocData(256);
        int np = putStr(es, Magic.bytes("escan"));       // "escan\0"
        long pp = es + np;
        Magic.store32(pp + 0, 1);                        // version = ESCAN_REQ_VERSION
        store16(pp + 4, 1);                              // action = WL_SCAN_ACTION_START
        store16(pp + 6, 0x1234);                         // sync_id
        int b = 0;
        while (b < 6)
        {
            Magic.store8(pp + 44 + b, 0xFF);             // bssid = broadcast
            b = b + 1;
        }
        Magic.store8(pp + 50, 2);                        // bss_type = DOT11_BSSTYPE_ANY
        Magic.store8(pp + 51, 0);                        // scan_type = active
        Magic.store32(pp + 52, -1);                      // nprobes
        Magic.store32(pp + 56, -1);                      // active_time
        Magic.store32(pp + 60, -1);                      // passive_time
        Magic.store32(pp + 64, -1);                      // home_time
        Magic.store32(pp + 68, 0);                       // channel_num = 0 (all)
        readCtrl(sendBcdc(WLC_SET_VAR, es, np + 72, true));   // escan ack (0 = scan started)

        // Read event frames (channel 1) for ~6 s and dump their ASCII — each escan result carries a
        // bss_info whose SSID shows up as a readable run. Control-channel (0) frames are acks, skip them.
        long rx = Heap.allocData(2048);
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 6L;
        int events = 0;
        while (Magic.readCNTPCT_EL0() < endT && events < 80)
        {
            int len = readFrameOnce(rx, 2048);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            int ch = Magic.load8(rx + 5) & 0x0F;
            if (ch == 0)
            {
                continue;                                // an ioctl ack, not an event
            }
            parseEvent(rx, len);
            events = events + 1;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: scan done\n"));
        joinOpen();                                      // M2: associate to the open network in wifi.conf
    }

    /**
     * Parse a firmware event frame and, for an escan result, print the AP's SSID. Layout: locate the BRCM
     * event ethertype 0x886C (this skips the leading BDC header/padding), then event_msg = ethertype(2) +
     * bcmeth_hdr(10) later; its fields are BIG-ENDIAN. event_type (69 = E_ESCAN_RESULT) at +4. The escan
     * result follows the 48-byte event_msg: brcmf_escan_result {buflen,version,sync_id,bss_count} then
     * bss_info {version,length,BSSID,beacon,capability,SSID_len@18,SSID@19}.
     */
    private static void parseEvent(long rx, int len)
    {
        int eth = -1;
        int i = 12;
        while (i < 64 && i < len - 2)                    // find 0x886C after the SDPCM/BDC headers
        {
            if ((Magic.load8(rx + i) & 0xFF) == 0x88 && (Magic.load8(rx + i + 1) & 0xFF) == 0x6C)
            {
                eth = i;
                break;
            }
            i = i + 1;
        }
        if (eth < 0)
        {
            return;                                      // not a BRCM event frame
        }
        long em = rx + eth + 2 + 10;                     // event_msg = after ethertype(2) + bcmeth_hdr(10)
        int et = beU32(em + 4);                          // event_type (big-endian)
        if (et != 69)
        {
            return;                                      // not E_ESCAN_RESULT
        }
        long esr = em + 48;                              // escan result after the 48-byte event_msg
        int bssCount = (Magic.load8(esr + 10) & 0xFF) | ((Magic.load8(esr + 11) & 0xFF) << 8);
        if (bssCount == 0)
        {
            return;                                      // scan-progress / completion, no AP
        }
        long bss = esr + 12;
        int ssidLen = Magic.load8(bss + 18) & 0xFF;
        if (ssidLen == 0 || ssidLen > 32)
        {
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("  SSID: "));
        int k = 0;
        while (k < ssidLen)
        {
            int c = Magic.load8(bss + 19 + k) & 0xFF;
            board.bcm2711.Uart.putc((c >= 0x20 && c < 0x7F) ? c : 0x2E);
            k = k + 1;
        }
        board.bcm2711.Uart.putc(0x0A);
    }

    /** Read a big-endian u32 (firmware event_msg fields are network-order). */
    private static int beU32(long addr)
    {
        return ((Magic.load8(addr) & 0xFF) << 24) | ((Magic.load8(addr + 1) & 0xFF) << 16)
                | ((Magic.load8(addr + 2) & 0xFF) << 8) | (Magic.load8(addr + 3) & 0xFF);
    }

    private static final int WLC_SET_INFRA    = 20;      // 1 = infrastructure (BSS), not IBSS
    private static final int WLC_SET_AUTH     = 22;      // 0 = open-system auth
    private static final int WLC_SET_SSID     = 26;      // associate to a wlc_ssid_t {len, SSID[32]}
    private static final int WLC_SET_WSEC     = 134;     // 0 = no link encryption
    private static final int WLC_SET_WPA_AUTH = 165;     // 0 = no WPA

    /**
     * M2 join (open network): read the SSID from {@code /etc/wifi.conf}, configure an open/unencrypted BSS
     * (INFRA=1, AUTH=0, wsec=0, wpa_auth=0), fire WLC_SET_SSID, then watch the event stream for the
     * association to come up — E_SET_SSID(53) then E_LINK(16) with the link flag set = joined.
     */
    static void joinOpen()
    {
        long e = VM.fileFind(Magic.bytes("/etc/wifi.conf"));
        if (e == 0L)
        {
            board.bcm2711.Uart.write(Magic.bytes("  no /etc/wifi.conf\n"));
            return;
        }
        long src = Magic.load64(e + 16L);
        int flen = (int) Magic.load64(e + 24L);
        long ssid = Heap.allocData(48);
        int sl = parseSsid(src, flen, ssid);
        if (sl == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  wifi.conf: no ssid\n"));
            return;
        }
        board.bcm2711.Uart.write(Magic.bytes("wifi: join "));
        dumpRaw(ssid, sl);

        setInt(WLC_SET_INFRA, 1);                        // infrastructure BSS
        setInt(WLC_SET_WSEC, 0);                         // open: no encryption
        setInt(WLC_SET_AUTH, 0);                         // open-system auth
        setInt(WLC_SET_WPA_AUTH, 0);                     // no WPA

        long ss = Heap.allocData(48);                    // wlc_ssid_t: len (u32) + SSID[32]
        Magic.store32(ss, sl);
        int i = 0;
        while (i < sl)
        {
            Magic.store8(ss + 4 + i, Magic.load8(ssid + i));
            i = i + 1;
        }
        readCtrl(sendBcdc(WLC_SET_SSID, ss, 36, true));

        // Wait up to ~15 s for the link-up event (trace every event in the meantime).
        long rx = Heap.allocData(2048);
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 15L;
        boolean linked = false;
        while (Magic.readCNTPCT_EL0() < endT && !linked)
        {
            int len = readFrameOnce(rx, 2048);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            if ((Magic.load8(rx + 5) & 0x0F) == 0)
            {
                continue;                                // ioctl ack
            }
            linked = parseJoinEvent(rx, len);
        }
        board.bcm2711.Uart.write(linked ? Magic.bytes("wifi: JOINED\n") : Magic.bytes("wifi: join timeout\n"));
    }

    /** Send a 4-byte (u32) SET ioctl and log its ack status. */
    private static void setInt(int cmd, int val)
    {
        long b = Heap.allocData(16);
        Magic.store32(b, val);
        readCtrl(sendBcdc(cmd, b, 4, true));
    }

    /** Copy the SSID from wifi.conf's first line into {@code dst} (strips an optional "ssid=" prefix and any
     *  trailing CR/LF); returns its length (<=32). */
    private static int parseSsid(long src, int flen, long dst)
    {
        int start = 0;
        if (flen >= 5 && (Magic.load8(src) & 0xFF) == 0x73 && (Magic.load8(src + 1) & 0xFF) == 0x73
                && (Magic.load8(src + 2) & 0xFF) == 0x69 && (Magic.load8(src + 3) & 0xFF) == 0x64
                && (Magic.load8(src + 4) & 0xFF) == 0x3D)                 // "ssid="
        {
            start = 5;
        }
        int n = 0;
        int i = start;
        while (i < flen && n < 32)
        {
            int c = Magic.load8(src + i) & 0xFF;
            if (c == 0x0A || c == 0x0D || c == 0)
            {
                break;
            }
            Magic.store8(dst + n, c);
            n = n + 1;
            i = i + 1;
        }
        return n;
    }

    /** Find the BRCM event ethertype 0x886C in a frame; returns its offset, or -1. */
    private static int findEthertype(long rx, int len)
    {
        int i = 12;
        while (i < 64 && i < len - 2)
        {
            if ((Magic.load8(rx + i) & 0xFF) == 0x88 && (Magic.load8(rx + i + 1) & 0xFF) == 0x6C)
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
    private static boolean parseJoinEvent(long rx, int len)
    {
        int eth = findEthertype(rx, len);
        if (eth < 0)
        {
            return false;
        }
        long em = rx + eth + 12;                         // event_msg = ethertype(2) + bcmeth_hdr(10)
        int flags = ((Magic.load8(em + 2) & 0xFF) << 8) | (Magic.load8(em + 3) & 0xFF);
        int et = beU32(em + 4);
        int status = beU32(em + 8);
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
     * Enable firmware events by reading the current {@code event_msgs} mask (GET tells us its exact length —
     * no guessing) and writing it back all-ones so every event, including E_ESCAN_RESULT, is delivered.
     */
    static void enableEvents()
    {
        long g = Heap.allocData(256);
        int p = putStr(g, Magic.bytes("event_msgs"));
        long rx = Heap.allocData(256);
        int id = sendBcdc(WLC_GET_VAR, g, p + 64, false);
        int len = recvCtrl(rx, 256, id);
        int mlen = 16;                                   // fallback
        if (len > 0)
        {
            int doff = Magic.load8(rx + 7) & 0xFF;
            int returned = Magic.load32(rx + doff + 4);  // BCDC len = mask length the fw uses
            if (returned > 0 && returned <= 64)
            {
                mlen = returned;
            }
        }
        log(Magic.bytes("evt masklen="), mlen);
        long s = Heap.allocData(256);
        int q = putStr(s, Magic.bytes("event_msgs"));
        int k = 0;
        while (k < mlen)
        {
            Magic.store8(s + q + k, 0xFF);
            k = k + 1;
        }
        readCtrl(sendBcdc(WLC_SET_VAR, s, q + mlen, true));
    }

    /** Print {@code len} bytes at {@code addr} as printable ASCII (non-printable -> '.'), not NUL-terminated. */
    private static void dumpRaw(long addr, int len)
    {
        int i = 0;
        while (i < len)
        {
            int c = Magic.load8(addr + i) & 0xFF;
            board.bcm2711.Uart.putc((c >= 0x20 && c < 0x7F) ? c : 0x2E);
            i = i + 1;
        }
        board.bcm2711.Uart.putc(0x0A);
    }

    /**
     * Download the CLM blob (regulatory + PHY calibration) the firmware needs before it can scan/join —
     * without it {@code WLC_SCAN} returns BCME_NOTUP. Sent as the chunked {@code clmload} iovar: each chunk
     * is a 12-byte brcmf_dload_data header {flag, dload_type=CLM, len, crc=0} + data, with DL_BEGIN on the
     * first chunk and DL_END on the last (flag also carries the handler version in bits 12+).
     */
    static void clmLoad()
    {
        long e = VM.fileFind(Magic.bytes("/lib/firmware/brcm/brcmfmac43455-sdio.clm_blob"));
        if (e == 0L)
        {
            board.bcm2711.Uart.write(Magic.bytes("  clm NOT FOUND\n"));
            return;
        }
        long src = Magic.load64(e + 16L);
        int total = (int) Magic.load64(e + 24L);
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
            long buf = Heap.allocData(512);
            int p = putStr(buf, Magic.bytes("clmload")); // "clmload\0"
            store16(buf + p, flag);
            store16(buf + p + 2, 2);                     // dload_type = DL_TYPE_CLM
            Magic.store32(buf + p + 4, n);               // chunk len
            Magic.store32(buf + p + 8, 0);               // crc unused
            int i = 0;
            while (i < n)
            {
                Magic.store8(buf + p + 12 + i, Magic.load8(src + off + i));
                i = i + 1;
            }
            readCtrl(sendBcdc(WLC_SET_VAR, buf, p + 12 + n, true));
            off = off + n;
        }
        board.bcm2711.Uart.write(Magic.bytes("  clm loaded\n"));
    }

    /** Build + send a BCDC ioctl (SET or GET) with {@code dataLen} bytes at {@code dataAddr} on the control
     *  channel; returns the request id. */
    static int sendBcdc(int cmd, long dataAddr, int dataLen, boolean isSet)
    {
        long tx = Heap.allocData(1024);
        int i = 0;
        while (i < dataLen)
        {
            Magic.store8(tx + 28 + i, Magic.load8(dataAddr + i));
            i = i + 1;
        }
        Magic.store32(tx + 12, cmd);
        Magic.store32(tx + 16, dataLen);
        Magic.store32(tx + 20, (reqId << 16) | (isSet ? 0x02 : 0));   // flags: id | SET
        Magic.store32(tx + 24, 0);
        int frameLen = 12 + 16 + dataLen;
        Magic.store8(tx + 4, txSeq & 0xFF);
        Magic.store8(tx + 5, 0);                         // control channel
        Magic.store8(tx + 6, 0);
        Magic.store8(tx + 7, 12);
        store16(tx + 0, frameLen);
        store16(tx + 2, ~frameLen);
        Sdio.cmd53Write(F2, 0, true, tx, 1, (frameLen + 3) & ~3);
        txSeq = txSeq + 1;
        int id = reqId;
        reqId = reqId + 1;
        return id;
    }

    /** Read the ioctl ack matching request {@code id} and log its status word (0 = OK). */
    static void readCtrl(int id)
    {
        long rx = Heap.allocData(1024);
        int len = recvCtrl(rx, 1024, id);
        if (len == 0)
        {
            board.bcm2711.Uart.write(Magic.bytes("  (no ctrl resp)\n"));
            return;
        }
        int doff = Magic.load8(rx + 7) & 0xFF;
        log(Magic.bytes("ctrl status="), Magic.load32(rx + doff + 12));
    }

    /**
     * Receive the control-channel BCDC response for request {@code wantId} into {@code dst}, skipping the
     * event/credit frames the firmware interleaves on other channels (they are logged). Returns the frame
     * length, or 0 on timeout. This is the RX dispatch: an ioctl ack is a channel-0 frame whose BCDC id
     * (flags bits 16..31) echoes the id we sent — anything else is an async event, not our answer.
     */
    static int recvCtrl(long dst, int cap, int wantId)
    {
        int tries = 0;
        while (tries < 600)
        {
            int len = readFrameOnce(dst, cap);
            if (len == 0)
            {
                tries = tries + 1;
                VM.delayMs(2);
                continue;
            }
            int ch = Magic.load8(dst + 5) & 0x0F;
            if (ch != 0)                                 // event (1) / data (2) — not an ioctl ack
            {
                log(Magic.bytes("  (skip ch="), ch);
                continue;
            }
            int doff = Magic.load8(dst + 7) & 0xFF;
            int id = (Magic.load32(dst + doff + 8) >> 16) & 0xFFFF;   // BCDC flags: id in bits 16..31
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
    private static int readFrameOnce(long dst, int cap)
    {
        if (!Sdio.cmd53Read(F2, 0, true, dst, 1, 4))     // hw header
        {
            return 0;
        }
        int hw = Magic.load32(dst);
        int len = hw & 0xFFFF;
        int nlen = (hw >> 16) & 0xFFFF;
        if (len < 12 || ((len ^ nlen) & 0xFFFF) != 0xFFFF)
        {
            return 0;                                    // no frame / invalid header
        }
        int body = len - 4;
        if (body > cap - 4)
        {
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
            if (!Sdio.cmd53Read(F2, 0, true, dst + 4 + done, 1, n))
            {
                return 0;
            }
            done = done + n;
        }
        return len;
    }

    /** Retry {@link #readFrameOnce} (1 ms apart) until a valid frame arrives or ~2 s elapses; 0 on timeout. */
    private static int waitFrame(long dst, int cap)
    {
        int tries = 0;
        while (tries < 2000)
        {
            int len = readFrameOnce(dst, cap);
            if (len > 0)
            {
                return len;
            }
            tries = tries + 1;
            VM.delayMs(1);
        }
        return 0;
    }

    /** Copy the bytes of {@code s} to {@code dst} then a NUL; returns the length written (incl. NUL). */
    private static int putStr(long dst, byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            Magic.store8(dst + i, s[i]);
            i = i + 1;
        }
        Magic.store8(dst + s.length, 0);
        return s.length + 1;
    }

    /** Store a 16-bit little-endian value (no Magic.store16). */
    private static void store16(long addr, int v)
    {
        Magic.store8(addr, v & 0xFF);
        Magic.store8(addr + 1, (v >> 8) & 0xFF);
    }

    /** Print up to {@code len} printable bytes at {@code addr} (NUL-terminated) to the UART. */
    private static void dumpAscii(long addr, int len)
    {
        int i = 0;
        while (i < len)
        {
            int c = Magic.load8(addr + i) & 0xFF;
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
    static boolean bpWrite(long bpAddr, long src, long byteLen)
    {
        long n = (byteLen + 3) & ~3L;                    // whole 32-bit words (a few trailing bytes harmless)
        long off = 0;
        while (off < n)
        {
            if (!bpWrite32(bpAddr + off, Magic.load32(src + off)))   // proven 4-byte path (setWindow cached)
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
        if (!Sdio.cmd53Read(F1, off, true, buf, 1, 4))
        {
            return 0;
        }
        return Magic.load32(buf);
    }

    /** Write a 32-bit word at backplane address {@code addr}. */
    static boolean bpWrite32(long addr, int value)
    {
        setWindow(addr);
        int off = (int) (addr & SB_WIN_MASK) | SB_ACCESS_4B;
        Magic.store32(buf, value);
        return Sdio.cmd53Write(F1, off, true, buf, 1, 4);
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
}
