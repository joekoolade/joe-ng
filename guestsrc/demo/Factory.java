package demo;

/**
 * A tiny functional interface for the constructor-reference probe: {@code make(int)} returns a new object.
 * {@code Num::new} targets it -- an invokedynamic whose impl MethodHandle is REF_newInvokeSpecial (kind 8),
 * so the synthetic-lambda thunk must alloc + run {@code <init>} + return the object, rather than call an
 * existing method.
 */
public interface Factory
{
    Object make(int v);
}
