/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */

import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * java.net.URL over the two protocols a class path is read through: {@code file:} and {@code jar:}. This is what
 * stock URLClassPath and ClassLoader.getResource stand on, and nothing else in the VM serves a URL today. The
 * jar is junit.jar wherever this side keeps it ({@code /lib/junit.jar} on metal, {@code ramfs/lib/junit.jar} on
 * the host), and every URL is printed from its last "lib/" on, so the two sides compare.
 */
public final class UrlProbe
{
    static final String SERVICES = "META-INF/services/org.junit.platform.engine.TestEngine";

    public static void main(String[] args) throws Throwable
    {
        File jar = new File("/lib/junit.jar").exists() ? new File("/lib/junit.jar") : new File("ramfs/lib/junit.jar");
        URL file = jar.getAbsoluteFile().toURI().toURL();
        URL entry = new URL("jar:" + file + "!/" + SERVICES);
        arm("file URL", () -> tail(file));
        arm("file URL head bytes", () -> head(file.openStream()));
        arm("file URL content length", () -> String.valueOf(file.openConnection().getContentLengthLong() == jar.length()));
        arm("jar URL", () -> tail(entry));
        arm("jar URL bytes", () -> bytes(entry.openStream()));
        arm("jar URL content length", () -> String.valueOf(entry.openConnection().getContentLength()));
        arm("JarURLConnection entry name", () -> ((JarURLConnection) entry.openConnection()).getEntryName());
        arm("JarURLConnection jar file URL", () -> tail(((JarURLConnection) entry.openConnection()).getJarFileURL()));
        arm("JarURLConnection getJarEntry", () -> ((JarURLConnection) entry.openConnection()).getJarEntry().getName());
        arm("JarURLConnection manifest", () ->
        {
            JarURLConnection c = (JarURLConnection) entry.openConnection();
            return String.valueOf(c.getManifest().getMainAttributes().getValue("Main-Class"));
        });
        arm("relative URL", () -> tail(new URL(entry, "other.txt")));
        arm("round trip equals", () -> String.valueOf(new URL(entry.toString()).equals(entry)));
        arm("to URI scheme", () -> entry.toURI().getScheme());
        arm("absent jar entry", () -> bytes(new URL("jar:" + file + "!/no/such/entry").openStream()));
        arm("absent jar file", () -> bytes(new URL("jar:file:/no/such/archive.jar!/x").openStream()));
        arm("absent file", () -> head(new URL("file:/no/such/file").openStream()));
        arm("unknown protocol", () -> tail(new URL("nosuchproto:/x")));
        arm("jar URL without !/", () -> tail(new URL("jar:" + file)));
        URLConnection uc = entry.openConnection();
        uc.setUseCaches(false);
        arm("uncached connection bytes", () -> bytes(uc.getInputStream()));
        System.out.println("UrlProbe done");
    }

    static String tail(URL u)
    {
        String s = u.toString();
        int at = s.lastIndexOf("lib/");
        return u.getProtocol() + " ..." + (at < 0 ? s : s.substring(at));
    }

    static String head(InputStream in) throws Exception
    {
        byte[] b = in.readNBytes(4);
        in.close();
        return String.format("%02x%02x%02x%02x", b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
    }

    static String bytes(InputStream in) throws Exception
    {
        byte[] b = in.readAllBytes();
        in.close();
        long sum = 0;
        for (byte x : b)
        {
            sum = sum * 31 + (x & 0xFF);
        }
        return b.length + " bytes hash=" + Long.toHexString(sum);
    }

    private interface Arm
    {
        String run() throws Throwable;
    }

    /** An exception is printed by class only: its message names a host path on one side and a RAMFS path on the other. */
    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName();
        }
        System.out.println(what + " = " + r);
    }
}
