package sun.net.ext;

import java.net.SocketOption;

/**
 * Minimal name-winning {@code sun.net.ext.ExtendedSocketOptions} overlay for joe-ng. Stock {@code getInstance}
 * loads a provider via reflection/ServiceLoader (the denied closure). {@code sun.nio.ch.Net.<clinit>} sets
 * {@code EXTENDED_OPTIONS = getInstance()} and {@code Net.getSocketOption} calls
 * {@code EXTENDED_OPTIONS.isOptionSupported(name)} on the close() SO_LINGER path -- a null EXTENDED_OPTIONS
 * NPEs there. joe-ng supports no extended (jdk.net) socket options, so this returns a singleton whose
 * {@code isOptionSupported} is always false, and Net falls through to the ordinary getIntOption0 path.
 */
public class ExtendedSocketOptions
{
    private static final ExtendedSocketOptions INSTANCE = new ExtendedSocketOptions();

    public static ExtendedSocketOptions getInstance()
    {
        return INSTANCE;
    }

    public boolean isOptionSupported(SocketOption<?> option)
    {
        return false;
    }
}
