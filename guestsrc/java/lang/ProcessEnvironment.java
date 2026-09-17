/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-02
 */
package java.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * The environment is EMPTY on joe-ng, and that is the truth rather than a stub: there is no OS beneath the VM,
 * so there is no process environment for a variable to come from.
 *
 * <p>Overlaid because stock's is a shell over the {@code environ()} NATIVE -- reached from its own
 * {@code <clinit>}, so merely touching {@code System.getenv} anywhere traps the whole class before any caller
 * gets an answer. JUnit reaches it through {@code System.getenv} while resolving configuration parameters, on
 * a path where "no such variable" is a perfectly good result: the caller falls back to its default.
 *
 * <p>Returning an empty map is therefore CORRECT, not a placeholder, and it is the reason this is an overlay
 * rather than a denylist entry -- a denylisted class would keep trapping, and the honest answer is available.
 */
final class ProcessEnvironment
{
    private static final Map<String, String> EMPTY = new HashMap<String, String>();

    private ProcessEnvironment()
    {
    }

    /** Always null: no environment, so no variable is set. */
    static String getenv(String name)
    {
        return null;
    }

    static Map<String, String> getenv()
    {
        return EMPTY;
    }

    static Map<String, String> environment()
    {
        return new HashMap<String, String>();           // stock returns a MODIFIABLE copy for ProcessBuilder
    }
}
