package java.net;

/**
 * A minimal, name-winning {@code java.net.InetAddress} overlay for joe-ng. Stock {@code getByName} pulls the
 * {@code InetAddressResolver} SPI / {@code ServiceLoader} / {@code Inet4AddressImpl} closure (denied on
 * metal), so this substitutes the class: an IPv4 held as a big-endian int, resolved from dotted-decimal
 * locally or via the WiFi DNS through the one native {@code resolve0}, wired to {@code VM.dnsResolve}.
 *
 * <p>The int is stored big-endian ({@code (a<<24)|(b<<16)|(c<<8)|d}); the socket layer reads it straight
 * out of this object. Field-minimal so the layout stays predictable.
 */
public class InetAddress
{
    int addr;   // IPv4, big-endian

    InetAddress()
    {
    }

    public static InetAddress getByName(String host) throws UnknownHostException
    {
        int dotted = parseDotted(host);
        // Return an Inet4Address, not a bare InetAddress: java.net.Socket.checkAddress / sun.nio.ch.Net
        // reject anything that is not instanceof Inet4Address || Inet6Address (throws IllegalArgumentException).
        Inet4Address a = new Inet4Address();
        if (dotted != -1)
        {
            a.addr = dotted;
            return a;
        }
        int r = resolve0(host.getBytes());
        if (r == 0)
        {
            throw new UnknownHostException(host);
        }
        a.addr = r;
        return a;
    }

    private static native int resolve0(byte[] host);   // -> VM.dnsResolve (WiFi DNS)

    /**
     * Address-class predicates. {@code NioSocketImpl.connect} tests {@code isAnyLocalAddress()} on the happy
     * path (line 577) and {@code Net.connect} tests {@code isLinkLocalAddress()}; both must be false for a
     * public routable target so the loopback / link-local-scoping branches (which reach the denied
     * {@code InetAddress.getLocalHost}/{@code IPAddressUtil.toScopedAddress}) are never taken. The rest are
     * provided for completeness so any stock caller resolves against this overlay, not the stock class.
     */
    public boolean isAnyLocalAddress()
    {
        return false;
    }

    public boolean isLinkLocalAddress()
    {
        return false;
    }

    public boolean isLoopbackAddress()
    {
        return false;
    }

    public boolean isMulticastAddress()
    {
        return false;
    }

    public boolean isSiteLocalAddress()
    {
        return false;
    }

    /**
     * Stub so the never-taken {@code isAnyLocalAddress()} branch in {@code NioSocketImpl.connect} (which
     * calls {@code InetAddress.getLocalHost()}) resolves at compile time. Never executed on the socket path.
     */
    public static InetAddress getLocalHost()
    {
        return new InetAddress();
    }

    public String getHostAddress()
    {
        return ((addr >> 24) & 0xFF) + "." + ((addr >> 16) & 0xFF) + "."
                + ((addr >> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    public byte[] getAddress()
    {
        byte[] b = new byte[4];
        b[0] = (byte) (addr >> 24);
        b[1] = (byte) (addr >> 16);
        b[2] = (byte) (addr >> 8);
        b[3] = (byte) addr;
        return b;
    }

    public String getHostName()
    {
        return getHostAddress();
    }

    /**
     * Build an address from raw bytes. {@code sun.nio.ch.Net.<clinit>} uses this (via {@code inet4FromInt} and
     * for the IPv6 wildcard/loopback) to seed its ANY_LOCAL/loopback constants. A 4-byte array is a normal
     * big-endian IPv4; a 16-byte (IPv6) array is accepted but collapsed to a plain handle (we do no IPv6, and
     * Net only asserts on these -- asserts are disabled).
     */
    public static InetAddress getByAddress(byte[] a) throws UnknownHostException
    {
        Inet4Address r = new Inet4Address();
        if (a != null && a.length == 4)
        {
            r.addr = ((a[0] & 0xFF) << 24) | ((a[1] & 0xFF) << 16) | ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
        }
        return r;
    }

    /**
     * The wildcard address 0.0.0.0. {@code new InetSocketAddress(addr, port)} calls this when {@code addr}
     * is null -- which happens on metal because {@code Net.localInetAddress} is stubbed to null, so
     * {@code Net.localAddress(fd)} (queried after connect) builds the local endpoint from the wildcard.
     */
    static InetAddress anyLocalAddress()
    {
        Inet4Address a = new Inet4Address();
        a.addr = 0;
        return a;
    }

    /** Parse {@code "a.b.c.d"} to a big-endian int, or -1 if it is not dotted-decimal. */
    private static int parseDotted(String s)
    {
        int val = 0;
        int part = 0;
        int parts = 0;
        int digits = 0;
        int i = 0;
        while (i < s.length())
        {
            char c = s.charAt(i);
            if (c == '.')
            {
                if (digits == 0 || part > 255)
                {
                    return -1;
                }
                val = (val << 8) | part;
                part = 0;
                digits = 0;
                parts = parts + 1;
            }
            else if (c >= '0' && c <= '9')
            {
                part = part * 10 + (c - '0');
                digits = digits + 1;
            }
            else
            {
                return -1;
            }
            i = i + 1;
        }
        if (digits == 0 || part > 255 || parts != 3)
        {
            return -1;
        }
        return (val << 8) | part;
    }
}
