package org.junit.jupiter.params.provider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A stand-in for {@code @MethodSource}: the runner calls the named factory method itself. */
@Retention(RetentionPolicy.RUNTIME)   // else javac writes RuntimeINVISIBLEAnnotations and the VM cannot see it
public @interface MethodSource
{
    String[] value() default {};
}
