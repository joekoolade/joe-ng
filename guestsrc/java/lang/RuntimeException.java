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

/** Mini {@code java/lang/RuntimeException} — super of the implicit exceptions the JIT throws. */
public class RuntimeException extends Exception
{
    public RuntimeException()
    {
    }

    public RuntimeException(String message)
    {
        super(message);
    }

    public RuntimeException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public RuntimeException(Throwable cause)
    {
        super(cause);
    }
}
