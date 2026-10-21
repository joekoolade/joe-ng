/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-20
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

/**
 * STOCK {@code java.security.Security}'s EXTRA properties file: the {@code java.security.properties} system
 * property names a file appended over the master one (the master file allows it with
 * {@code security.overridePropertiesFile=true}). The name goes through {@code PropertyExpander.expand} -- here it
 * is {@code ${probe.secdir}/extra.security}, so an unexpanded name would miss -- and the extra file
 * {@code include}s a second one by a RELATIVE name, which stock resolves against the including file's path.
 *
 * <p>Both files are written before {@code Security} is first touched, since the extra file is read once, by its
 * initializer. The argument is the tree's root: empty on the metal, on a host a writable directory with a
 * {@code tmp/} (see NioWriteProbe). Values only; the host diff is the gate. The scratch files are removed.
 */
public class SecurityExtraProbe
{
    public static void main(String[] args) throws Exception
    {
        String root = args.length > 0 ? args[0] : "";
        Path dir = Path.of(root + "/tmp");
        Path extra = dir.resolve("extra.security");
        Path inc = dir.resolve("inc.security");
        Files.writeString(extra, "probe.extra.key=from-extra\n"
                + "networkaddress.cache.negative.ttl=3\n"
                + "include inc.security\n"
                + "probe.after.include=after\n");
        Files.writeString(inc, "probe.inc.key=from-include\n"
                + "probe.extra.key=overridden-by-include\n");
        System.setProperty("probe.secdir", dir.toString());
        System.setProperty("java.security.properties", "${probe.secdir}/extra.security");

        String[] keys = {
            "probe.extra.key",
            "probe.inc.key",
            "probe.after.include",
            "networkaddress.cache.negative.ttl",
            "networkaddress.cache.ttl",
            "security.overridePropertiesFile",
        };
        for (int i = 0; i < keys.length; i += 1)
        {
            System.out.println(keys[i] + "=" + Security.getProperty(keys[i]));
        }

        Files.delete(extra);
        Files.delete(inc);
        System.out.println("scratch removed=" + (!Files.exists(extra) && !Files.exists(inc)));
        System.out.println("SecurityExtraProbe done");
    }
}
