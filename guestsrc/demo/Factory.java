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
package demo;

/**
 * A tiny functional interface for the constructor-reference probe: {@code make(int)} returns a new object.
 * {@code Num::new} targets it -- an invokedynamic whose impl MethodHandle is REF_newInvokeSpecial (kind 8),
 * so the synthetic-lambda thunk must alloc + run {@code <init>} + return the object, rather than call an
 * existing method.
 */
public interface Factory
{
    Object make(int v);
}
