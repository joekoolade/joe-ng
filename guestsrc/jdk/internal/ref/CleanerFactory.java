/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package jdk.internal.ref;

import java.lang.ref.Cleaner;

/** Name-winning overlay: hands out the one synchronous {@link Cleaner} (see the Cleaner overlay). */
public final class CleanerFactory
{
    private static final Cleaner COMMON = Cleaner.create();

    private CleanerFactory()
    {
    }

    public static Cleaner cleaner()
    {
        return COMMON;
    }
}
