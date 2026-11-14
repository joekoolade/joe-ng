/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package hello;

/** The jar's implementation of its own interface. */
final class Square implements Shape
{
    private final int side;

    Square(int side)
    {
        this.side = side;
    }

    @Override
    public String name()
    {
        return "square " + side;
    }

    @Override
    public int area()
    {
        return side * side;
    }
}
