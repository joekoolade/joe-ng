package org.junit.jupiter.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A stand-in for JUnit 5's {@code @AfterEach}. Only the marker is needed: joe-ng's hand-written runners call
 * the lifecycle method explicitly, in the order the annotation would have implied.
 */
@Retention(RetentionPolicy.RUNTIME)   // else javac writes RuntimeINVISIBLEAnnotations and the VM cannot see it
public @interface AfterEach
{
}
