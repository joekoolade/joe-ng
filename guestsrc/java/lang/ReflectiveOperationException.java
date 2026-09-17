/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-13
 */
package java.lang;

/** Mini {@code java/lang/ReflectiveOperationException} — common supertype of the reflection checked
 *  exceptions ({@link ClassNotFoundException}, {@code NoSuchFieldException}, ...). Kept so reflection code that
 *  catches the family supertype works on metal. */
public class ReflectiveOperationException extends Exception
{
    public ReflectiveOperationException()
    {
    }

    public ReflectiveOperationException(String message)
    {
        super(message);
    }

    public ReflectiveOperationException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public ReflectiveOperationException(Throwable cause)
    {
        super(cause);
    }
}
