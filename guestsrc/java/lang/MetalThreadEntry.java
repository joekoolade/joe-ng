/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-09
 */
package java.lang;

/**
 * joe-ng's thread ENTRY POINT. The VM's run-trampoline ({@code Loader.resolveRun}) prefers this interface's one
 * method over {@code Runnable.run} when it starts a {@link Thread}, so {@link Thread#metalThreadEntry} can run the
 * thread's {@code run()} and hand an escaping throwable to the thread's {@code UncaughtExceptionHandler} -- the job
 * a stock JVM does by calling {@code Thread.dispatchUncaughtException} from native code. Without it a handler set
 * with {@code setUncaughtExceptionHandler} would be accepted and NEVER called.
 *
 * <p>An interface, not a method the trampoline names: the trampoline already resolves through an itable entry, and
 * a {@code Thread} subclass overriding {@code run()} must still enter here. Package-private, so it is not API.
 */
interface MetalThreadEntry
{
    void metalThreadEntry();
}
