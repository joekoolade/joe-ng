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

/** Mini {@code java/lang/NoSuchMethodException} — thrown by {@link Class#getDeclaredMethod}/{@code getMethod}
 *  and {@code getDeclaredConstructor} when no matching member exists. */
public class NoSuchMethodException extends ReflectiveOperationException
{
    public NoSuchMethodException()
    {
    }

    public NoSuchMethodException(String message)
    {
        super(message);
    }
}
