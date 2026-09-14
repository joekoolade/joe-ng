package jdk.internal.reflect;

import java.lang.reflect.Constructor;

/**
 * The sliver of {@code jdk.internal.reflect.ReflectionFactory} that serialization's DESCRIBE path touches.
 *
 * <p>{@code java.io.ObjectStreamClass.<init>} calls
 * {@code getReflectionFactory().newConstructorForSerialization(cl)} for every serializable non-record class
 * it describes -- and describing a class is all that {@code ObjectStreamClass.lookup} does, which is what
 * {@code org.junit.platform.engine.UniqueId}'s initializer needs. Without this the call trapped as a
 * DENYLIST TRAP (a real one: {@code jdk/internal/reflect/} is denied as a prefix), so the denial is narrowed
 * to this class alone and the rest of the package stays denied.
 *
 * <p>The rest of the real ReflectionFactory is the bytecode-generating accessor machinery, which this VM
 * deliberately does not carry.
 */
public final class ReflectionFactory
{
    private static final ReflectionFactory SOLE = new ReflectionFactory();

    private ReflectionFactory()
    {
    }

    public static ReflectionFactory getReflectionFactory()
    {
        return SOLE;
    }

    /**
     * The no-arg constructor of the first non-serializable superclass, used to build an instance during
     * DESERIALIZATION without running the serializable class's own constructor.
     *
     * <p>Answers {@code null}, and that is a STOCK-LEGAL answer rather than a stub's guess: the caller's own
     * contract is "or null if none found" ({@code ObjectStreamClass.getSerializableConstructor}), so
     * {@code ObjectStreamClass} already handles it -- it stores the field and only dereferences it when
     * something actually DESERIALIZES.
     *
     * <p>So DESCRIBING a class works, which is what is reached here, and deserializing would fail at the
     * point it tries to use this -- the honest place for it, rather than here where nothing is wrong yet.
     * Manufacturing a constructor that skipped the right superclass initializer would be far worse: an object
     * that looks built and is not.
     */
    public Constructor<?> newConstructorForSerialization(Class<?> cl)
    {
        return null;
    }
}
