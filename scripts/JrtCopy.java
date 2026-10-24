/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-24
 */

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copy one file out of the RUNNING JDK's modules image ({@code jrt:/}) -- a java.base RESOURCE such as
 * {@code java/util/currency.data}, which lives inside lib/modules rather than beside it the way tzdb.dat does.
 *
 * <p>Usage: {@code java scripts/JrtCopy.java <module>/<path> <dest file>}. The seed's own java.base is the one
 * the writer bakes, so its resources are the files those classes were built to read.
 */
public final class JrtCopy
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            System.err.println("usage: java scripts/JrtCopy.java <module>/<path> <dest file>");
            System.exit(2);
        }
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path src = jrt.getPath("/modules", args[0]);
        Path dst = Path.of(args[1]);
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("  " + args[0] + " -> " + dst + " (" + Files.size(dst) + " bytes)");
    }
}
