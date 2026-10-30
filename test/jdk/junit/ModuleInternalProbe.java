/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-30
 */

import java.io.ByteArrayInputStream;
import java.lang.module.ModuleDescriptor;
import java.util.TreeSet;

/**
 * jdk/internal/module/ is no longer denied. The public java.lang.module API routes through it:
 * ModuleDescriptor.read parses with jdk.internal.module.ModuleInfo, and ModuleDescriptor.newModule checks names
 * with jdk.internal.module.Checks -- both trap-wired while the package was denied. The descriptor read here is a
 * real javac output (`module probe.m { requires java.logging; uses java.lang.Runnable; }`, --release 17), embedded
 * as bytes. The by-name arms run three of the package's initializers (Modules, Checks, ModuleBootstrap, whose
 * <clinit> reads ModuleLoaderMap) and an enum's constants. Each arm runs on its own and prints what it got.
 */
public class ModuleInternalProbe
{
    static final byte[] MODULE_INFO = {
            -54, -2, -70, -66, 0, 0, 0, 61, 0, 15, 7, 0, 2, 1, 0, 11, 109, 111, 100, 117, 108, 101, 45, 105, 110,
            102, 111, 1, 0, 10, 83, 111, 117, 114, 99, 101, 70, 105, 108, 101, 1, 0, 16, 109, 111, 100, 117, 108,
            101, 45, 105, 110, 102, 111, 46, 106, 97, 118, 97, 1, 0, 6, 77, 111, 100, 117, 108, 101, 19, 0, 7, 1, 0,
            7, 112, 114, 111, 98, 101, 46, 109, 19, 0, 9, 1, 0, 9, 106, 97, 118, 97, 46, 98, 97, 115, 101, 1, 0, 2,
            49, 55, 19, 0, 12, 1, 0, 12, 106, 97, 118, 97, 46, 108, 111, 103, 103, 105, 110, 103, 7, 0, 14, 1, 0,
            18, 106, 97, 118, 97, 47, 108, 97, 110, 103, 47, 82, 117, 110, 110, 97, 98, 108, 101, -128, 0, 0, 1, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 3, 0, 0, 0, 2, 0, 4, 0, 5, 0, 0, 0, 30, 0, 6, 0, 0, 0, 0, 0, 2, 0, 8,
            -128, 0, 0, 10, 0, 11, 0, 0, 0, 10, 0, 0, 0, 0, 0, 1, 0, 13, 0, 0
    };

    interface Arm
    {
        String run() throws Throwable;
    }

    static void report(String what, Arm arm)
    {
        String got;
        try
        {
            got = arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName();
        }
        System.out.println(what + ": " + got);
    }

    public static void main(String[] args)
    {
        report("read module-info", () ->
        {
            ModuleDescriptor d = ModuleDescriptor.read(new ByteArrayInputStream(MODULE_INFO));
            TreeSet<String> req = new TreeSet<>();
            for (ModuleDescriptor.Requires r : d.requires())
            {
                req.add(r.name());
            }
            return d.name() + " requires=" + req + " uses=" + d.uses();
        });
        report("newModule valid", () ->
        {
            ModuleDescriptor d = ModuleDescriptor.newModule("a.b").requires("java.sql").exports("a.b.c").build();
            return d.name() + " exports=" + d.exports().iterator().next().source();
        });
        report("newModule bad name", () ->
        {
            ModuleDescriptor.newModule("bad name!");
            return "accepted";
        });
        report("newModule reserved", () ->
        {
            ModuleDescriptor.newModule("a.class.b");
            return "accepted";
        });
        report("initializers", () ->
        {
            String s = "";
            for (String n : new String[] { "Modules", "Checks", "ModuleBootstrap" })
            {
                s += Class.forName("jdk.internal.module." + n).getSimpleName() + " ";
            }
            return s.trim();
        });
        report("IllegalNativeAccess", () ->
        {
            Object[] k = Class.forName("jdk.internal.module.ModuleBootstrap$IllegalNativeAccess").getEnumConstants();
            String s = "";
            for (Object o : k)
            {
                s += o + " ";
            }
            return s.trim();
        });
        System.out.println("ModuleInternalProbe done");
    }
}
