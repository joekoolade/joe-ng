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
        InetAddress a = new InetAddress();
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
