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
package java.lang.reflect;

/** Mini {@code java/lang/reflect/InvocationTargetException} — wraps an exception thrown by a reflectively
 *  invoked {@code Method}/{@code Constructor}. (The metal invoke path currently lets the target's exception
 *  propagate directly; kept for the {@code Method.invoke} signature and future wrapping.) */
public class InvocationTargetException extends ReflectiveOperationException
{
    private final Throwable target;

    public InvocationTargetException()
    {
        this.target = null;
    }

    public InvocationTargetException(Throwable target)
    {
        super(target);
        this.target = target;
    }

    public Throwable getTargetException()
    {
        return target;
    }

    public Throwable getCause()
    {
        return target;
    }
}
