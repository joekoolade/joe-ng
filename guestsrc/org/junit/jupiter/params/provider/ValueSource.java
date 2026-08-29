package org.junit.jupiter.params.provider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A stand-in for {@code @ValueSource}: the runner passes each listed value in turn. */
@Retention(RetentionPolicy.RUNTIME)   // else javac writes RuntimeINVISIBLEAnnotations and the VM cannot see it
public @interface ValueSource
{
    String[] strings() default {};
    int[] ints() default {};
    long[] longs() default {};
    boolean[] booleans() default {};
}
