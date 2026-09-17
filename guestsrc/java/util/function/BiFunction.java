/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-16
 */
package java.util.function;

/**
 * Minimal generic {@code BiFunction} — a two-argument {@link Function}. Only the SAM ({@code apply(T,U)->R}) is
 * provided; the JDK's default {@code andThen} is omitted until something needs it. {@code BinaryOperator<T>}
 * extends {@code BiFunction<T,T,T>}, so a {@code BinaryOperator} lambda IS a {@code BiFunction} — which is what
 * lets stock {@code Stream.reduce}/{@code ReduceOps} invoke the accumulator through {@code BiFunction.apply}.
 */
public interface BiFunction<T, U, R>
{
    R apply(T t, U u);
}
