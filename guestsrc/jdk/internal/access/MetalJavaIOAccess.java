package jdk.internal.access;

import java.io.Console;

/**
 * The {@code JavaIOAccess} shim for joe-ng, seeded into {@code SharedSecrets} by
 * {@code Loader.seedJavaIOAccess} exactly as {@link MetalJavaLangAccess} is.
 *
 * <p>Stock registers its own from {@code java.io.Console}'s static initializer, which needs a tty; joe-ng has
 * a UART and no tty, so nothing ever registers one and {@code SharedSecrets.getJavaIOAccess()} answers null.
 * That is not merely a gap: {@code System.console()} is
 *
 * <pre>cons = SharedSecrets.getJavaIOAccess().console();</pre>
 *
 * with no null check, so the missing shim turns every {@code System.console()} call into an NPE thrown from
 * inside java.base. JUnit's {@code TestConsoleOutputOptions.<clinit>} calls it (through
 * {@code ConsoleUtils.charset()}) and the console launcher died there.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class, it supplies one the platform is expected to register and
 * this platform cannot. A new class, like MetalJavaLangAccess.
 *
 * <p>{@code console()} returns NULL, which is the TRUE answer rather than a stub: there is no
 * {@code java.io.Console} here, and every stock caller is written for it --
 * {@code ConsoleUtils.charset()} is literally
 * {@code c != null ? c.charset() : Charset.defaultCharset()}. Fabricating a Console would make those callers
 * take a path this VM cannot honour.
 */
public final class MetalJavaIOAccess implements JavaIOAccess
{
    /** No tty on bare metal, so there is no Console. Null is what every stock caller checks for. */
    @Override
    public Console console()
    {
        return null;
    }

    /** stdin is the UART, which is not a terminal in the sense this asks about. */
    @Override
    public boolean isStdinTty()
    {
        return false;
    }
}
