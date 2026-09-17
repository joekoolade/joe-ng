/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-27
 */
package demo;

/**
 * The shape that broke the zip JUnit harness, reduced: a functional interface with a STATIC factory, exactly
 * like JUnit's {@code Arguments}. {@code registerInterface} gives itable indices to {@code isVirtual} methods
 * only, so {@link #tag()} gets no itable index, no vtable slot and no static cell — it is registered nowhere
 * dispatchable, even once the interface itself is loaded.
 */
public interface RtaIface
{
    String describe();

    static String tag()
    {
        return "iface-static";
    }
}
