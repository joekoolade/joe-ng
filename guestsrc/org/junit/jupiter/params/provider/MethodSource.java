package org.junit.jupiter.params.provider;

/** A stand-in for {@code @MethodSource}: the runner calls the named factory method itself. */
public @interface MethodSource
{
    String[] value() default {};
}
