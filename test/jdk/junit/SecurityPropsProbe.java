/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-12
 */
import java.security.Security;

/**
 * STOCK {@code java.security.Security} PROPERTIES: the store stock {@code InetAddress}'s resolver cache reads its
 * TTLs from. On this VM the master file is generated at build time from the JDK tree's template for linux-aarch64
 * and read out of the RAMFS; the host reads its own JDK's file. Only keys that a macOS and a Linux JDK ship with the
 * same value are printed (the provider list differs at slot 12: {@code Apple} vs {@code SunPKCS11}), plus the
 * reserved {@code include} key and a null key. Prints values only; the host diff is the gate.
 */
public class SecurityPropsProbe
{
    public static void main(String[] args)
    {
        String[] keys = {
            "networkaddress.cache.ttl",
            "networkaddress.cache.negative.ttl",
            "networkaddress.cache.stale.ttl",
            "security.overridePropertiesFile",
            "security.provider.1",
            "security.provider.11",
            "crypto.policy",
            "keystore.type",
            "jdk.includeInExceptions",
            "securerandom.source",
            "no.such.property",
        };
        for (int i = 0; i < keys.length; i += 1)
        {
            System.out.println(keys[i] + "=" + Security.getProperty(keys[i]));
        }
        String cert = Security.getProperty("jdk.certpath.disabledAlgorithms");
        System.out.println("certpath starts=" + cert.substring(0, 14) + " multiline=" + (cert.indexOf("SHA1") > 0));

        // NO set/get round trip: a successful setProperty goes on to jdk.internal.event.EventHelper, whose
        // <clinit> needs Lookup.findStaticVarHandle and whose isLoggingSecurity needs System.getLogger (the denied
        // jdk/internal/logger). That traps loudly on this VM -- a recorded gap, not what this probe measures.
        // The reserved key below is rejected by checkReservedKey BEFORE any of that, so it is safe to check.
        try
        {
            Security.setProperty("include", "x");
            System.out.println("include accepted");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("include: " + e.getMessage());
        }
        try
        {
            Security.getProperty(null);
            System.out.println("null key accepted");
        }
        catch (NullPointerException e)
        {
            System.out.println("null key: NPE");
        }
        System.out.println("SecurityPropsProbe done");
    }
}
