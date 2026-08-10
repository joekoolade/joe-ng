package java.net;

/**
 * Name-winning {@code Inet4Address} overlay for joe-ng. Stock {@code Inet4Address} stores its state in the
 * {@code InetAddress.holder()} (an {@code InetAddressHolder} the minimal overlay doesn't carry). We only need
 * the class to EXIST as a subtype of {@link InetAddress}: {@code java.net.Socket.checkAddress} (and
 * {@code sun.nio.ch.Net.checkAddress}) reject any address that is not {@code instanceof Inet4Address ||
 * instanceof Inet6Address}. So {@link InetAddress#getByName} returns an {@code Inet4Address}, and this overlay
 * inherits the whole (big-endian int) implementation from {@link InetAddress} -- it adds nothing but identity.
 */
class Inet4Address extends InetAddress
{
    Inet4Address()
    {
        super();
    }
}
