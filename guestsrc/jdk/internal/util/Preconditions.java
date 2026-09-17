/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-30
 */
package jdk.internal.util;

import java.util.function.BiFunction;

/**
 * Bare-metal override of {@code jdk.internal.util.Preconditions} — the bounds-check helper stock java.base
 * routes every index/range check through ({@code String.charAt}, {@code Objects.checkIndex}, {@code
 * ArrayList.get}, {@code String.substring}, ...). Stock builds its out-of-bounds message through a heavy path
 * ({@code outOfBounds -> List.of -> ImmutableCollections} + a {@code BiFunction} formatter + {@code
 * String.format}); on metal we do the same check and throw a real {@link IndexOutOfBoundsException} instead.
 * Callers read the {@code *_FORMATTER} fields (present below, inert) and pass one in; we ignore it. The generics
 * are erased away — the descriptors callers link against are just {@code (II...Ljava/util/function/BiFunction;)I},
 * so raw {@code BiFunction} matches. Only the {@code int} overloads java.base reaches on metal are provided; a
 * {@code long} overload is added on demand if {@code jitFail} names it.
 */
public class Preconditions
{
    // Stock callers getstatic these before calling checkIndex/checkFromToIndex; we build no message, so they
    // are inert (null). Assigned in <clinit> (a null reference constant carries no ConstantValue attribute).
    public static final BiFunction SIOOBE_FORMATTER = null;
    public static final BiFunction AIOOBE_FORMATTER = null;
    public static final BiFunction IOOBE_FORMATTER = null;

    public static int checkIndex(int index, int length, BiFunction oobef)
    {
        if (index < 0 || index >= length)
        {
            throw new IndexOutOfBoundsException();
        }
        return index;
    }

    public static int checkFromToIndex(int fromIndex, int toIndex, int length, BiFunction oobef)
    {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length)
        {
            throw new IndexOutOfBoundsException();
        }
        return fromIndex;
    }

    public static int checkFromIndexSize(int fromIndex, int size, int length, BiFunction oobef)
    {
        if (length < 0 || fromIndex < 0 || size < 0 || size > length - fromIndex)
        {
            throw new IndexOutOfBoundsException();
        }
        return fromIndex;
    }
}
