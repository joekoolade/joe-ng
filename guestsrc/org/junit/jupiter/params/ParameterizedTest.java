package org.junit.jupiter.params;

/**
 * A stand-in for JUnit 5's {@code @ParameterizedTest}. The runner supplies the arguments directly, one
 * call per case, so only the marker (and its optional {@code name} attribute) has to exist.
 */
public @interface ParameterizedTest
{
    String name() default "";
}
