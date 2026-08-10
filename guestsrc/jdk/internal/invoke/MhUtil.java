package jdk.internal.invoke;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Minimal name-winning {@code jdk.internal.invoke.MhUtil} overlay for joe-ng. Stock {@code findVarHandle}
 * reflects a field into a real {@code VarHandle} via the Lookup. On metal we bind our {@link VarHandle}
 * overlay to the field NAME only (the offset is resolved from the target object at call time); the Lookup
 * and declared type are unused.
 */
public final class MhUtil
{
    private MhUtil()
    {
    }

    public static VarHandle findVarHandle(MethodHandles.Lookup lookup, String name, Class type)
    {
        return bind(name);
    }

    public static VarHandle findVarHandle(MethodHandles.Lookup lookup, Class recv, String name, Class type)
    {
        return bind(name);
    }

    private static VarHandle bind(String name)
    {
        return VarHandle.ofField(name.getBytes());
    }
}
