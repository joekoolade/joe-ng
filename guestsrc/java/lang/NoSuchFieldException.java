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

/** Mini {@code java/lang/NoSuchFieldException} — thrown by {@link Class#getField}/{@code getDeclaredField} when
 *  no matching field exists (or none that is accessible for the {@code getField} public-only lookup). */
public class NoSuchFieldException extends ReflectiveOperationException
{
    public NoSuchFieldException()
    {
    }

    public NoSuchFieldException(String message)
    {
        super(message);
    }
}
