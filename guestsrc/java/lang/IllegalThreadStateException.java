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

/** Mini {@code java/lang/IllegalThreadStateException} — thrown by Thread.join(Duration) on an unstarted thread. */
public class IllegalThreadStateException extends RuntimeException
{
    public IllegalThreadStateException()
    {
    }

    public IllegalThreadStateException(String message)
    {
        super(message);
    }
}
