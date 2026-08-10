package sun.nio.ch;

/**
 * Minimal name-winning {@code sun.nio.ch.DirectBuffer} overlay: just the {@code address()} the socket
 * dispatcher reads. Stock also declares attachment()/cleaner() (which pull jdk.internal.ref.Cleaner); the
 * socket path only casts a temporary buffer to DirectBuffer for its address, so this is all that's needed.
 */
public interface DirectBuffer
{
    long address();
}
