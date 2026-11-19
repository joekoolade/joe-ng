/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package board.virt;

import board.Board;
import board.bcm2711.Uart;
import magic.Address;
import magic.Magic;
import vm.VM;

/**
 * The entropy source on QEMU {@code virt}: a virtio-rng device ({@code -device virtio-rng-device}) on the machine's
 * virtio-mmio transports, driven through one polled virtqueue. It stands where the Pi's RNG200 stands --
 * {@code board.bcm2711.Rng} hands over to it on a virt image -- with the same contract: {@link #fill} answers how
 * many bytes it really wrote, never pads, and {@link #available} refuses a device whose samples do not vary.
 *
 * <p>MODERN virtio only (virtio-mmio version 2, Virtio 1.x §4.2.2): QEMU's transports default to the LEGACY
 * interface, so the runner passes {@code -global virtio-mmio.force-legacy=false}; without it the device is found
 * and reported as legacy, and not used. Polled, not interrupt-driven: entropy is drawn rarely (a SecureRandom seed),
 * so a short wait for the device beats wiring an SPI.
 *
 * <p>The virtqueue -- descriptor table, driver ring, device ring and the 256-byte buffer the device writes -- lives
 * in one fixed page, {@link #PAGE}. The device reaches it by PHYSICAL address, so every address handed to it goes
 * through {@link Board#phys}. One draw at a time: {@link #fill} holds a spinlock with IRQs masked.
 */
public final class VirtioRng
{
    private VirtioRng()
    {
    }

    /** The virtqueue page (virtual): inside the Pi mailbox's scratch slot, which a virt image never uses. */
    public static final long PAGE = 0x0305_8000L;
    public static final long PAGE_END = PAGE + 0x1000L;
    private static final long DESC = PAGE;               // QSIZE descriptors, 16 bytes each
    private static final long AVAIL = PAGE + 0x100L;      // flags, idx, ring[QSIZE] (u16s)
    private static final long USED = PAGE + 0x200L;       // flags, idx, ring[QSIZE] of {u32 id, u32 len}
    private static final long BUF = PAGE + 0x400L;        // what the device fills
    private static final long LOCK = PAGE + 0x800L;       // spinlock word
    private static final int BUF_LEN = 256;
    private static final int QSIZE = 4;

    /** QEMU virt: 32 virtio-mmio transports of 0x200 bytes from physical 0x0A00_0000 (virtual: + VIRT_DEV_VA). */
    private static final long MMIO_BASE = 0x0A00_0000L + Board.VIRT_DEV_VA;
    private static final int MMIO_SLOTS = 32;
    private static final long MMIO_STRIDE = 0x200L;

    private static final long REG_MAGIC = 0x000;
    private static final long REG_VERSION = 0x004;
    private static final long REG_DEVICE_ID = 0x008;
    private static final long REG_DEVICE_FEATURES = 0x010;
    private static final long REG_DEVICE_FEATURES_SEL = 0x014;
    private static final long REG_DRIVER_FEATURES = 0x020;
    private static final long REG_DRIVER_FEATURES_SEL = 0x024;
    private static final long REG_QUEUE_SEL = 0x030;
    private static final long REG_QUEUE_NUM_MAX = 0x034;
    private static final long REG_QUEUE_NUM = 0x038;
    private static final long REG_QUEUE_READY = 0x044;
    private static final long REG_QUEUE_NOTIFY = 0x050;
    private static final long REG_STATUS = 0x070;
    private static final long REG_QUEUE_DESC_LO = 0x080;
    private static final long REG_QUEUE_DESC_HI = 0x084;
    private static final long REG_QUEUE_DRIVER_LO = 0x090;
    private static final long REG_QUEUE_DRIVER_HI = 0x094;
    private static final long REG_QUEUE_DEVICE_LO = 0x0A0;
    private static final long REG_QUEUE_DEVICE_HI = 0x0A4;

    private static final int MAGIC = 0x7472_6976;         // "virt"
    private static final int DEVICE_ENTROPY = 4;
    private static final int ST_ACKNOWLEDGE = 1;
    private static final int ST_DRIVER = 2;
    private static final int ST_DRIVER_OK = 4;
    private static final int ST_FEATURES_OK = 8;
    private static final int VIRTQ_DESC_F_WRITE = 2;      // the device writes this buffer

    private static final int UNPROBED = 0;
    private static final int USABLE = 1;
    private static final int UNUSABLE = 2;
    public static final int WHY_ABSENT = 1;               // no entropy device on any transport
    public static final int WHY_LEGACY = 2;               // found, but only the legacy (version 1) interface
    public static final int WHY_REFUSED = 3;              // the device refused FEATURES_OK or has no queue
    public static final int WHY_TIMEOUT = 4;              // a request never completed
    public static final int WHY_STUCK = 5;                // samples did not vary
    private static final int SAMPLES = 4;

    private static int state;
    private static int why;
    private static long base;                             // the transport the device is on (virtual)
    private static int nextAvail;                          // the driver ring's idx
    private static int lastUsed;                           // the device ring's idx as last consumed

    /** {@return whether a usable virtio-rng device is present} Probes once and latches, like the RNG200's. */
    public static boolean available()
    {
        if (state != UNPROBED)
        {
            return state == USABLE;
        }
        state = UNUSABLE;
        if (!findAndInit())
        {
            return false;
        }
        // The same refusal as the RNG200's: a source whose samples never vary would seed every SecureRandom
        // identically, with nothing in the output to show it.
        long first = 0L;
        boolean varies = false;
        int i = 0;
        while (i < SAMPLES)
        {
            if (draw(8) != 8)
            {
                why = WHY_TIMEOUT;
                return false;
            }
            long w = Address.fromLong(BUF).loadLong();
            if (i == 0)
            {
                first = w;
            }
            else if (w != first)
            {
                varies = true;
            }
            i += 1;
        }
        if (!varies)
        {
            why = WHY_STUCK;
            return false;
        }
        why = 0;
        state = USABLE;
        return true;
    }

    /** {@return a reason the device was rejected} 0 once it is usable. */
    public static int whyUnusable()
    {
        return why;
    }

    /** The transport the device was found on (virtual address), for the boot report; 0 if none. */
    public static long transport()
    {
        return base;
    }

    /**
     * Fill {@code len} bytes of entropy at {@code dst}. Returns how many were written: short of {@code len} if the
     * device stopped answering, 0 if there is no usable device. A caller treats a short answer as a failure.
     */
    public static int fill(long dst, int len)
    {
        if (!available())
        {
            return 0;
        }
        long daif = Magic.readDaif();
        Magic.disableIrq();
        Magic.spinLock(LOCK);
        int done = 0;
        while (done < len)
        {
            int want = len - done;
            if (want > BUF_LEN)
            {
                want = BUF_LEN;
            }
            int got = draw(want);
            if (got <= 0)
            {
                break;
            }
            int k = 0;
            while (k < got)
            {
                Address.fromLong(dst).plus(done + k).store(Address.fromLong(BUF).plus(k).loadByte());
                k += 1;
            }
            done += got;
        }
        Magic.spinUnlock(LOCK);
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
        return done;
    }

    /** Find the entropy device among the transports and bring it to DRIVER_OK with queue 0 set up. */
    private static boolean findAndInit()
    {
        why = WHY_ABSENT;
        int slot = 0;
        while (slot < MMIO_SLOTS)
        {
            long b = MMIO_BASE + slot * MMIO_STRIDE;
            if (reg(b, REG_MAGIC) == MAGIC && reg(b, REG_DEVICE_ID) == DEVICE_ENTROPY)
            {
                base = b;
                break;
            }
            slot += 1;
        }
        if (base == 0L)
        {
            return false;
        }
        if (reg(base, REG_VERSION) != 2)
        {
            why = WHY_LEGACY;
            return false;
        }
        why = WHY_REFUSED;
        setReg(REG_STATUS, 0);                                       // reset
        setReg(REG_STATUS, ST_ACKNOWLEDGE);
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER);
        setReg(REG_DEVICE_FEATURES_SEL, 1);                          // features 32..63
        if ((reg(base, REG_DEVICE_FEATURES) & 1) == 0)
        {
            return false;                                            // no VIRTIO_F_VERSION_1 (bit 32)
        }
        setReg(REG_DRIVER_FEATURES_SEL, 1);
        setReg(REG_DRIVER_FEATURES, 1);                              // accept VERSION_1, nothing else
        setReg(REG_DRIVER_FEATURES_SEL, 0);
        setReg(REG_DRIVER_FEATURES, 0);
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER | ST_FEATURES_OK);
        if ((reg(base, REG_STATUS) & ST_FEATURES_OK) == 0)
        {
            return false;
        }
        setReg(REG_QUEUE_SEL, 0);
        if (reg(base, REG_QUEUE_NUM_MAX) < QSIZE)
        {
            return false;
        }
        long p = PAGE;
        while (p < PAGE_END)
        {
            Address.fromLong(p).store(0L);                           // empty rings, zeroed descriptors
            p += 8L;
        }
        nextAvail = 0;
        lastUsed = 0;
        setReg(REG_QUEUE_NUM, QSIZE);
        setAddr(REG_QUEUE_DESC_LO, REG_QUEUE_DESC_HI, Board.phys(DESC));
        setAddr(REG_QUEUE_DRIVER_LO, REG_QUEUE_DRIVER_HI, Board.phys(AVAIL));
        setAddr(REG_QUEUE_DEVICE_LO, REG_QUEUE_DEVICE_HI, Board.phys(USED));
        setReg(REG_QUEUE_READY, 1);
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER | ST_FEATURES_OK | ST_DRIVER_OK);
        Address.fromLong(LOCK).store(0);
        why = 0;
        return true;
    }

    /**
     * One request: descriptor 0 points the device at {@link #BUF} for {@code len} bytes; publish it on the driver
     * ring, notify, and wait (at most ~100 ms) for the device ring to advance. Returns the bytes written, or 0.
     */
    private static int draw(int len)
    {
        Address d = Address.fromLong(DESC);
        d.store(Board.phys(BUF));                                    // addr
        d.plus(8L).store(len);                                       // len (u32)
        d.plus(12L).store((short) VIRTQ_DESC_F_WRITE);               // flags
        d.plus(14L).store((short) 0);                                // next
        Address.fromLong(AVAIL + 4L + (nextAvail % QSIZE) * 2L).store((short) 0);   // ring[] = descriptor 0
        Magic.dsb();                                                 // the descriptor before the index
        nextAvail = (nextAvail + 1) & 0xFFFF;
        Address.fromLong(AVAIL + 2L).store((short) nextAvail);
        Magic.dsb();                                                 // the index before the notify
        setReg(REG_QUEUE_NOTIFY, 0);
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 10L;
        while ((Address.fromLong(USED + 2L).loadChar() & 0xFFFF) == lastUsed)
        {
            if (Magic.readCNTPCT_EL0() > deadline)
            {
                return 0;
            }
        }
        Magic.dsb();                                                 // the device's writes before we read them
        int written = Address.fromLong(USED + 4L + (lastUsed % QSIZE) * 8L + 4L).loadInt();
        lastUsed = (lastUsed + 1) & 0xFFFF;
        if (written > len)
        {
            written = len;
        }
        return written;
    }

    private static int reg(long b, long off)
    {
        return Address.fromLong(b + off).loadInt();
    }

    private static void setReg(long off, int v)
    {
        Address.fromLong(base + off).store(v);
    }

    private static void setAddr(long lo, long hi, long pa)
    {
        setReg(lo, (int) pa);
        setReg(hi, (int) (pa >>> 32));
    }

    /** Print what the probe found, as one boot line. Called once from boot (through the platform). */
    public static void report()
    {
        Uart.write(Magic.bytes("hw rng: "));
        if (available())
        {
            Uart.write(Magic.bytes("virtio-rng at 0x"));
            VM.printHex(transport() - Board.VIRT_DEV_VA);
            Uart.write(Magic.bytes(", live\n"));
            return;
        }
        int w = whyUnusable();
        if (w == WHY_ABSENT)
        {
            Uart.write(Magic.bytes("absent (no virtio-rng device: run QEMU with -device virtio-rng-device)\n"));
        }
        else if (w == WHY_LEGACY)
        {
            Uart.write(Magic.bytes("virtio-rng is LEGACY only (add -global virtio-mmio.force-legacy=false)\n"));
        }
        else if (w == WHY_REFUSED)
        {
            Uart.write(Magic.bytes("virtio-rng refused setup (features or queue)\n"));
        }
        else if (w == WHY_TIMEOUT)
        {
            Uart.write(Magic.bytes("virtio-rng never completed a request\n"));
        }
        else
        {
            Uart.write(Magic.bytes("virtio-rng STUCK (samples did not vary) -- refusing to trust it\n"));
        }
    }
}
