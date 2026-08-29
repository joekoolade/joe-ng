package org.junit.jupiter.params;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A stand-in for JUnit 5's {@code @ParameterizedTest}. The runner supplies the arguments directly, one
 * call per case, so only the marker (and its optional {@code name} attribute) has to exist.
 */
@Retention(RetentionPolicy.RUNTIME)   // else javac writes RuntimeINVISIBLEAnnotations and the VM cannot see it
public @interface ParameterizedTest
{
    String name() default "";
}
