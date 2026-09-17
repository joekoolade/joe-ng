/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-28
 */
package java.util.function;

/**
 * Minimal {@code BinaryOperator}. It extends {@code BiFunction<T,T,T>} exactly like the JDK, so a
 * {@code BinaryOperator} lambda's functional-interface method IS {@code BiFunction.apply} — otherwise stock
 * {@code Stream.reduce(identity, accumulator)} / {@code ReduceOps}, which store the accumulator as a
 * {@code BiFunction} and invoke {@code BiFunction.apply}, would dispatch into an itable that never carried that
 * interface method and NPE. The {@code apply(T,T)} SAM is inherited from {@code BiFunction}.
 */
public interface BinaryOperator<T> extends BiFunction<T, T, T>
{
}
