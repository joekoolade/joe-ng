package org.junit.jupiter.api;

/**
 * A stand-in for JUnit 5's {@code @BeforeAll}. Only the marker is needed: joe-ng's hand-written runners call
 * the lifecycle method explicitly, in the order the annotation would have implied.
 */
public @interface BeforeAll
{
}
