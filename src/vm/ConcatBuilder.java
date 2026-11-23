/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-23
 */
package vm;

/**
 * The string-concat builder ({@link VMConcat}): a real object, so a collector can SEE its backing array.
 *
 * <p>It used to be a header-only heap block with TIB 0, which every trace reads as "bytes, no references" -- so
 * nothing followed {@code buf}, and a collection in the middle of a concat (a String argument's {@code toString}, a
 * float's formatter, the builder growing) could free the array the builder was still filling. Rare with a
 * non-moving heap; certain with the nursery, which copies what it can see and reclaims the rest (frames/GC arc S7-4,
 * CharsetIdentityProbe printed garbage in place of its first line).
 *
 * <p>VMConcat still addresses the fields by offset -- {@code +16} buf, {@code +24} count, {@code +32} the largest
 * unit -- which is {@code ObjectModel.fieldOffset} of declaration indices 0, 1 and 2. Keep the order.
 */
final class ConcatBuilder
{
    byte[] buf;         // +16: the UTF-16 code units, two bytes each, low byte first
    long count;         // +24: units appended
    long maxUnit;       // +32: the largest unit appended (decides the result's coder)
}
