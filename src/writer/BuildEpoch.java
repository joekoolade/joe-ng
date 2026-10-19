/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
package writer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * The time the image's source was committed, baked as the wall clock's starting point ({@code vm/VM.wallEpochMs}).
 * It follows the {@code SOURCE_DATE_EPOCH} convention: that variable wins when set (seconds since the epoch), and
 * otherwise the committer time of {@code HEAD} -- never the time of the build, which would make every rebuild of one
 * commit a different image. Uncommitted edits do not move it; it stays a lower bound on the time either way.
 *
 * <p>A build that can answer neither FAILS rather than baking 0: 0 is the 1970 clock this replaces.
 */
final class BuildEpoch
{
    static final long MILLIS = seconds() * 1000L;

    private BuildEpoch()
    {
    }

    private static long seconds()
    {
        String env = System.getenv("SOURCE_DATE_EPOCH");
        if (env != null && !env.isEmpty())
        {
            return Long.parseLong(env.trim());
        }
        String line = null;
        try
        {
            Process p = new ProcessBuilder("git", "log", "-1", "--format=%ct").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream())))
            {
                line = r.readLine();
            }
            if (p.waitFor() != 0)
            {
                line = null;
            }
        }
        catch (IOException e)
        {
            line = null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            line = null;
        }
        if (line == null || !line.trim().matches("[0-9]+"))
        {
            throw new IllegalStateException("no build epoch: set SOURCE_DATE_EPOCH or build inside the git tree"
                    + " (git log -1 --format=%ct answered " + line + ")");
        }
        return Long.parseLong(line.trim());
    }
}
