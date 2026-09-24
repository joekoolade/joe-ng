/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-25
 */
package jdk.internal.misc;

/**
 * A JDK-free {@code jdk.internal.misc.CDS} overlay (wins by name). Class-data sharing is a hosted-JVM feature
 * — a memory-mapped archive of pre-initialized class state — and every entry point into it is native. joe-ng
 * has no archive, so every query answers "not archived" and every hook is a no-op, which is exactly the
 * behaviour a hosted JVM has when CDS is off.
 *
 * <p>It is here because {@code java.util.jar.Attributes$Name.<clinit>} calls
 * {@code CDS.initializeFromArchive} before building its well-known {@code Name} constants — one native call
 * standing between the stock {@code Manifest} parser and running on metal.
 */
public final class CDS
{
    private CDS()
    {
    }

    /** No archived state exists, so the class's own initializer builds everything itself. */
    public static void initializeFromArchive(Class<?> c)
    {
    }

    public static boolean isDumpingArchive()
    {
        return false;
    }

    public static boolean isSharingEnabled()
    {
        return false;
    }

    public static boolean isDumpingClassList()
    {
        return false;
    }

    public static boolean isDumpingHeap()
    {
        return false;
    }

    /**
     * NATIVE, as stock declares it -- wired in {@code Loader.nativeBufAt} to the writer-baked build seed.
     *
     * <p>It returned a hardcoded {@code 0L} here, which is stock's "not dumping" signal, so
     * {@code ImmutableCollections.<clinit>} fell through to {@code System.nanoTime()} and computed a salt
     * that differed on every boot -- while the writer had separately baked a salt that differed on every
     * BUILD, from the host's clock. A joe-ng image IS a dumped archive, and this is the hook stock provides
     * for exactly that case, so the writer supplies the seed and both sides now derive the same value.
     * ImmutableCollections's initializer still runs normally; only the seed it reads changed.
     */
    public static native long getRandomSeedForDumping();

    public static void logLambdaFormInvoker(String line)
    {
    }
}
