package org.junit.jupiter.params.provider;

/**
 * A stand-in for JUnit 5's {@code Arguments}: one row of a {@code @MethodSource} table. Stock tests build
 * these with {@code Arguments.of(...)} and the engine spreads them across the test method's parameters;
 * joe-ng's runner reads them back with {@link #get()} and passes them explicitly.
 */
public interface Arguments
{
    Object[] get();

    static Arguments of(Object... args)
    {
        return () -> args;
    }

    static Arguments arguments(Object... args)
    {
        return of(args);
    }
}
