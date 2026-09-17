/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
package java.lang;

/** Mini {@code java/lang/NullPointerException} — the JIT synthesises one on a null deref (VM.newNpe). */
public class NullPointerException extends RuntimeException
{
    public NullPointerException()
    {
    }

    public NullPointerException(String message)
    {
        super(message);   // -> Throwable.detailMessage, read by getMessage() (Objects.requireNonNull(o, msg))
    }
}
