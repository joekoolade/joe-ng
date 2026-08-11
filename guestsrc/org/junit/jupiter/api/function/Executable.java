package org.junit.jupiter.api.function;

/** JUnit's functional interface for a block that may throw (the target of {@code assertThrows} lambdas). */
@FunctionalInterface
public interface Executable
{
    void execute() throws Throwable;
}
