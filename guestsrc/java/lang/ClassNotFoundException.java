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

/** Mini {@code java/lang/ClassNotFoundException} — thrown by {@link Class#forName(String)} when the named
 *  class cannot be located (not embedded, or an invalid binary name). Real hierarchy: extends
 *  {@link ReflectiveOperationException} extends {@link Exception}. */
public class ClassNotFoundException extends ReflectiveOperationException
{
    public ClassNotFoundException()
    {
    }

    public ClassNotFoundException(String message)
    {
        super(message);
    }

    public ClassNotFoundException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
