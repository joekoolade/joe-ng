/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-26
 */
package java.nio;

/**
 * A JDK-free {@code java.nio.ByteOrder} overlay. The stock class is tiny but its {@code <clinit>} asks
 * {@code Unsafe}/{@code Bits} for the platform endianness, which is not reachable on metal; joe-ng targets
 * AArch64 running little-endian, so {@link #nativeOrder} answers that directly.
 */
public final class ByteOrder
{
    public static final ByteOrder BIG_ENDIAN = new ByteOrder("BIG_ENDIAN");
    public static final ByteOrder LITTLE_ENDIAN = new ByteOrder("LITTLE_ENDIAN");

    private final String name;

    private ByteOrder(String name)
    {
        this.name = name;
    }

    public static ByteOrder nativeOrder()
    {
        return LITTLE_ENDIAN;                            // AArch64 EL1, little-endian (SCTLR_EL1.EE = 0)
    }

    public String toString()
    {
        return name;
    }
}
