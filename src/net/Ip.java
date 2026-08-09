package net;

import magic.Magic;
import vm.Heap;

/**
 * The IP/link foundation of joe-ng's all-Java network stack, factored out of the CYW43 WiFi driver so a
 * socket layer (java.net, via VM helpers) can drive it independently of the board. Holds the interface
 * config (our MAC/IP + the gateway MAC) that the driver's DHCP/ARP bring-up publishes, the one's-complement
 * checksum + big-endian helpers, and the link glue (send an 802.3 frame / receive the next IPv4 frame) that
 * delegates to the board driver's SDPCM data path.
 *
 * <p>All addresses are raw heap pointers and all packet fields are poked via {@link Magic} — this is the
 * same byte-for-byte code the WiFi HTTP-GET path used, only relocated behind a reusable API.
 */
public final class Ip
{
    private Ip() {}

    // Interface config, published by the driver bring-up (board.cyw43.Cyw43, after DHCP + ARP).
    public static long ourMac;      // heap addr of our 6-byte station MAC
    public static long ourIp;       // heap addr of our 4-byte leased IP
    public static long gwMac;       // heap addr of the gateway's 6-byte MAC (all off-subnet traffic routes here)

    // Scratch describing the last IPv4 frame recvIp() accepted.
    public static int lastIhl;      // IP header length (bytes)
    public static int lastIpTotal;  // IP total length (bytes)

    /** Send a fully-built 802.3 Ethernet frame over the WiFi data path. */
    public static void send(long frame, int len)
    {
        board.cyw43.Cyw43.txData(frame, len);
    }

    /**
     * Block for the next inbound IPv4 frame (up to {@code ms} ms); return the IP header address (inside
     * {@code rx}), or 0 on none/timeout/non-IPv4. Records IHL + total-length in {@link #lastIhl}/{@link
     * #lastIpTotal}. Mirrors the driver's parse: skip the SDPCM data-offset + 4-byte BDC header to the 802.3
     * frame, require ethertype 0x0800.
     */
    public static long recvIp(long rx, int cap, int ms)
    {
        int len = board.cyw43.Cyw43.waitFrameIrq(rx, cap, ms);
        if (len == 0)
        {
            return 0L;
        }
        if ((Magic.load8(rx + 5) & 0x0F) == 0)          // SDPCM control channel, not data
        {
            return 0L;
        }
        long eth = rx + (Magic.load8(rx + 7) & 0xFF);   // SDPCM data offset
        eth = eth + 4 + (Magic.load8(eth + 3) & 0xFF) * 4;   // skip the 4-byte BDC header
        if (((Magic.load8(eth + 12) & 0xFF) << 8 | (Magic.load8(eth + 13) & 0xFF)) != 0x0800)
        {
            return 0L;                                   // not IPv4
        }
        long ip = eth + 14;
        lastIhl = (Magic.load8(ip) & 0x0F) * 4;
        lastIpTotal = ((Magic.load8(ip + 2) & 0xFF) << 8) | (Magic.load8(ip + 3) & 0xFF);
        return ip;
    }

    /** One's-complement IPv4 header checksum over {@code len} bytes at {@code addr}. */
    public static int cksum(long addr, int len)
    {
        int sum = 0;
        int i = 0;
        while (i < len)
        {
            sum = sum + (((Magic.load8(addr + i) & 0xFF) << 8) | (Magic.load8(addr + i + 1) & 0xFF));
            i = i + 2;
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Store a 16-bit big-endian (network-order) value. */
    public static void be16(long addr, int v)
    {
        Magic.store8(addr, (v >> 8) & 0xFF);
        Magic.store8(addr + 1, v & 0xFF);
    }

    /** Store a 32-bit big-endian value. */
    public static void be32(long addr, int v)
    {
        Magic.store8(addr, (v >> 24) & 0xFF);
        Magic.store8(addr + 1, (v >> 16) & 0xFF);
        Magic.store8(addr + 2, (v >> 8) & 0xFF);
        Magic.store8(addr + 3, v & 0xFF);
    }

    /** Read a 32-bit big-endian value. */
    public static int readBe32(long addr)
    {
        return ((Magic.load8(addr) & 0xFF) << 24) | ((Magic.load8(addr + 1) & 0xFF) << 16)
                | ((Magic.load8(addr + 2) & 0xFF) << 8) | (Magic.load8(addr + 3) & 0xFF);
    }

    /** Compare two 4-byte IPv4 addresses. */
    public static boolean eq(long a, long b)
    {
        int i = 0;
        while (i < 4)
        {
            if ((Magic.load8(a + i) & 0xFF) != (Magic.load8(b + i) & 0xFF))
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    /** Copy 4 bytes (an IPv4 address). */
    public static void copy4(long src, long dst)
    {
        int i = 0;
        while (i < 4)
        {
            Magic.store8(dst + i, Magic.load8(src + i));
            i = i + 1;
        }
    }
}
