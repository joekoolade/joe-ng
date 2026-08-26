package org.junit.jupiter.params.provider;

/** A stand-in for {@code @ValueSource}: the runner passes each listed value in turn. */
public @interface ValueSource
{
    String[] strings() default {};
    int[] ints() default {};
    long[] longs() default {};
    boolean[] booleans() default {};
}
