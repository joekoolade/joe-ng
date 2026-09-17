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
package plugin;

/**
 * An external "plugin" class for joe-ng reflection arc M4. It is compiled into {@code ramfs/plugins/} (NOT the
 * loader's classDir), so on the metal it exists ONLY as a file the guest reads and hands to
 * {@code ClassLoader.defineClass} — never reachable by {@code forName}. Dependency-free (just {@code Object} +
 * int math) so plain {@code javac} against the seed JDK produces a classfile the loader parses directly.
 */
public class FilePlugin
{
    private int seed;

    public FilePlugin()
    {
        seed = 100;
    }

    public int scale(int x)
    {
        return seed + x * 3;                             // 100 + 7*3 = 121 -- proves ctor field + method both ran
    }
}
