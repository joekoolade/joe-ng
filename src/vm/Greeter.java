/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-19
 */
package vm;

/**
 * An interface the loader loads on the metal. It is loaded <em>before</em> its
 * implementors so its methods get their global itable indices first; then every
 * implementing class builds an imap indexed by those, and an
 * {@code invokeinterface} call site resolves to the same index.
 *
 * <p>{@link Alpha} and {@link Beta} both implement it, deliberately at
 * <em>different</em> vtable slots — which is exactly the case a vtable slot alone
 * cannot express, and why the itable exists.
 */
public interface Greeter
{
    int greet();
}
