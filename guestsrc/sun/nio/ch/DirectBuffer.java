/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package sun.nio.ch;

/**
 * Minimal name-winning {@code sun.nio.ch.DirectBuffer} overlay: just the {@code address()} the socket
 * dispatcher reads. Stock also declares attachment()/cleaner() (which pull jdk.internal.ref.Cleaner); the
 * socket path only casts a temporary buffer to DirectBuffer for its address, so this is all that's needed.
 */
public interface DirectBuffer
{
    long address();
}
