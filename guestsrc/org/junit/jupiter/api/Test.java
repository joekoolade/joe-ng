package org.junit.jupiter.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A stand-in for JUnit 5's {@code @Test}. joe-ng cannot host the JUnit engine (reflection/annotations/
 * ServiceLoader), so a tiny hand-written runner invokes the annotated methods directly; this marker exists
 * only so the unmodified test source compiles. The annotation attribute is skipped at class load.
 */
@Retention(RetentionPolicy.RUNTIME)   // else javac writes RuntimeINVISIBLEAnnotations and the VM cannot see it
public @interface Test
{
}
