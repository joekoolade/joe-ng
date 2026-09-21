/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */
package java.security;

import java.util.Properties;

/**
 * A cryptographic service provider -- here, a NAME, a version and a description.
 *
 * <p>OVERLAID, and this is a stated exception to "guestsrc is only for classes that need natives", the same
 * exception {@code java.util.ServiceLoader} takes: stock's 2000-line {@code Provider} IS the pluggable
 * service registry -- entries parsed from a {@code java.security} properties FILE, providers discovered
 * through {@code ServiceLoader} over the module graph, services reflected into being through
 * {@code java.lang.invoke}. Every one of those three subsystems is deliberately absent here, so no faithful
 * copy could work whatever its shape.
 *
 * <p>What joe-ng has instead is ONE built-in provider, {@link #JOENG}, implemented directly by the VM's own
 * {@code crypto/} engines. So a {@code Provider} on this VM answers what it truthfully can -- who it is --
 * and nothing more. It still {@code extends Properties} as stock does, because a name-winning overlay that
 * DROPS the stock superclass deletes it for every caller: that is the {@code PrintStream}-dropping-
 * {@code OutputStream} trap, which cost this project a launcher that printed absolutely nothing.
 *
 * <p>NOT declared, and named rather than faked: {@code Provider.Service}, {@code getService},
 * {@code put}/{@code remove} registration, and the {@code Provider.Service} reflection that builds an
 * instance from a class name. A caller reaching for those wants a pluggable provider, and this VM has none
 * -- a method answering null there would read as "no such algorithm" when the truth is "no such mechanism".
 */
public class Provider extends Properties
{
    /**
     * The built-in provider: the digests implemented in {@code crypto/}. Its name is "joe-ng" rather than
     * an imitation of "SUN", for the reason {@code os.name} says joe-ng -- code that branches on the
     * provider should not be told something false.
     */
    public static final Provider JOENG = new Provider("joe-ng", "1.0", "joe-ng built-in digests (MD5, SHA-1, SHA-2)");

    private final String name;
    private final String versionStr;
    private final String info;

    /**
     * A provider with the given identity.
     *
     * @param name       its name
     * @param versionStr its version string
     * @param info       a human-readable description
     */
    protected Provider(String name, String versionStr, String info)
    {
        this.name = name;
        this.versionStr = versionStr;
        this.info = info;
    }

    /** {@return this provider's name} */
    public String getName()
    {
        return name;
    }

    /** {@return this provider's version string} */
    public String getVersionStr()
    {
        return versionStr;
    }

    /** {@return a human-readable description of this provider} */
    public String getInfo()
    {
        return info;
    }

    /** {@return the name and version, which is what stock's {@code toString} gives} */
    @Override
    public String toString()
    {
        return name + " version " + versionStr;
    }
}
