package java.net;

/**
 * Name-winning {@code SocksSocketImpl} overlay for joe-ng. {@code Socket.createImpl()} ALWAYS wraps the
 * platform impl ({@code NioSocketImpl}) in a {@code SocksSocketImpl} -- even for a direct, no-proxy
 * connection -- so this class is on the taken path, not a "never-taken proxy" branch (denying it was wrong).
 *
 * <p>Stock {@code SocksSocketImpl} overrides {@code connect} to consult {@code DefaultProxySelector} /
 * {@code sun.net.spi} (the proxy machinery, unavailable/denied on metal). Its superclass
 * {@code DelegatingSocketImpl} already forwards every {@code SocketImpl} method to the delegate. So this
 * overlay adds only the constructors and inherits pure delegation: {@code create}/{@code connect}/
 * {@code getInputStream}/{@code getOutputStream}/{@code close}/... all go straight to the stock
 * {@code NioSocketImpl} delegate, with no proxy logic and no proxy-selector closure.
 */
class SocksSocketImpl extends DelegatingSocketImpl
{
    SocksSocketImpl(SocketImpl delegate)
    {
        super(delegate);
    }

    SocksSocketImpl(Proxy proxy, SocketImpl delegate)
    {
        super(delegate);
    }
}
