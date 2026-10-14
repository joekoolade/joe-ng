/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.security.Security;

/**
 * STOCK {@code Security.setProperty}: the write half of the security-property store. Stock reaches
 * {@code jdk.internal.event.EventHelper} on every set -- a static {@code VarHandle} built by
 * {@code findStaticVarHandle} in its initializer, a {@code compareAndSet} on it, and
 * {@code System.getLogger("jdk.event.security")} at DEBUG -- so a set that "works" must run all of that. The shapes:
 * a new key read back, an existing key overwritten and read back, a second set (the logger is looked up once), the
 * reserved {@code include} key and null key/value refused. Prints values only; the host diff is the gate
 * ({@code --limit-modules java.base}).
 */
public class SecuritySetProbe
{
    public static void main(String[] args)
    {
        System.out.println("before=" + Security.getProperty("probe.new.key"));
        Security.setProperty("probe.new.key", "one");
        System.out.println("after set=" + Security.getProperty("probe.new.key"));
        Security.setProperty("probe.new.key", "two");
        System.out.println("after reset=" + Security.getProperty("probe.new.key"));
        Security.setProperty("networkaddress.cache.negative.ttl", "3");
        System.out.println("overwritten=" + Security.getProperty("networkaddress.cache.negative.ttl"));
        try
        {
            Security.setProperty("include", "x");
            System.out.println("include: accepted");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("include: IllegalArgumentException");
        }
        try
        {
            Security.setProperty(null, "x");
            System.out.println("null key: accepted");
        }
        catch (NullPointerException e)
        {
            System.out.println("null key: NullPointerException");
        }
        try
        {
            Security.setProperty("probe.k", null);
            System.out.println("null value: accepted");
        }
        catch (NullPointerException e)
        {
            System.out.println("null value: NullPointerException");
        }
        System.out.println("SecuritySetProbe done");
    }
}
