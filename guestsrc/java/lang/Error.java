/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.lang;

/** Mini {@code java/lang/Error} — super of AssertionError (the JUnit-lite assertions throw it). */
public class Error extends Throwable
{
    public Error()
    {
    }

    public Error(String message)
    {
        super(message);
    }

    public Error(String message, Throwable cause)
    {
        super(message, cause);
    }
}
