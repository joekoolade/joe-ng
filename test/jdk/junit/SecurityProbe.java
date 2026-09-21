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
import java.security.AllPermission;
import java.security.BasicPermission;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Enumeration;
import java.util.PropertyPermission;

/**
 * {@code java.security} on the metal -- the STOCK permission/marker layer, which needs no natives and no
 * provider machinery. It was denied only by the broad {@code java/security/} prefix.
 *
 * <p>The arms are chosen so a PARTIAL implementation fails rather than passes:
 *
 * <ul>
 * <li>{@code "a.b.*" implies "a.b.c"} is satisfied by a naive {@code startsWith} prefix match. The arms that
 *     DISCRIMINATE are {@code "a.b.*" implies "a.bc"} (must be FALSE -- a prefix match says true) and
 *     {@code "a.b.*" implies "a.b"} (must be FALSE -- stock requires the target be strictly LONGER).
 * <li>{@code implies} is class-sensitive: two DIFFERENT BasicPermission subclasses with the SAME name must
 *     not imply each other, and must not be {@code equals}. An implementation comparing names alone passes
 *     every other arm.
 * <li>{@code Permissions} is a HETEROGENEOUS collection -- it must route each permission to its own
 *     per-class collection. One class's grant must not satisfy another class's request.
 * <li>{@code hashCode} must be the NAME's hash, which is what makes equal permissions collide in a hash
 *     container; identity hashing passes {@code equals} and silently breaks the collection.
 * </ul>
 */
public class SecurityProbe
{
    /** A BasicPermission subclass. Two distinct ones are what make the class-sensitivity arms meaningful. */
    public static class Alpha extends BasicPermission
    {
        public Alpha(String name)
        {
            super(name);
        }
    }

    /** A SECOND subclass with the same shape: same names, different class. */
    public static class Beta extends BasicPermission
    {
        public Beta(String name)
        {
            super(name);
        }
    }

    /** A Principal, to exercise the interface plus its {@code implies} default. */
    public static class Named implements Principal
    {
        private final String name;

        public Named(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }
    }

    private static void say(String what, Object got, Object want)
    {
        boolean ok = got == null ? want == null : got.equals(want);
        System.out.println((ok ? "ok   " : "FAIL ") + what + " = " + got + " (want " + want + ")");
    }

    public static void main(String[] args) throws Exception
    {
        // ---- BasicPermission wildcard matching -------------------------------------------------------
        Alpha star = new Alpha("*");
        Alpha abStar = new Alpha("a.b.*");
        Alpha abc = new Alpha("a.b.c");

        say("* implies a.b.c", star.implies(abc), Boolean.TRUE);
        say("a.b.* implies a.b.c", abStar.implies(abc), Boolean.TRUE);
        say("a.b.* implies a.b.c.d", abStar.implies(new Alpha("a.b.c.d")), Boolean.TRUE);

        // THE DISCRIMINATORS: a prefix match answers true to both of these, and both must be false.
        say("a.b.* implies a.bc", abStar.implies(new Alpha("a.bc")), Boolean.FALSE);
        say("a.b.* implies a.b", abStar.implies(new Alpha("a.b")), Boolean.FALSE);

        say("a.b.c implies a.b.*", abc.implies(abStar), Boolean.FALSE);
        say("a.b.c implies a.b.c", abc.implies(new Alpha("a.b.c")), Boolean.TRUE);
        say("a.b.c implies a.b.d", abc.implies(new Alpha("a.b.d")), Boolean.FALSE);
        say("a.b.* implies null", abStar.implies(null), Boolean.FALSE);

        // ---- class sensitivity: name equality is NOT enough ------------------------------------------
        say("Alpha(x) implies Beta(x)", new Alpha("x").implies(new Beta("x")), Boolean.FALSE);
        say("Alpha(*) implies Beta(x)", star.implies(new Beta("x")), Boolean.FALSE);
        say("Alpha(x).equals(Beta(x))", new Alpha("x").equals(new Beta("x")), Boolean.FALSE);
        say("Alpha(x).equals(Alpha(x))", new Alpha("x").equals(new Alpha("x")), Boolean.TRUE);

        // ---- the Permission contract -----------------------------------------------------------------
        say("getName", abc.getName(), "a.b.c");
        say("getActions", abc.getActions(), "");
        say("hashCode == name hash", Integer.valueOf(abc.hashCode()), Integer.valueOf("a.b.c".hashCode()));
        say("toString", abc.toString(), "(\"SecurityProbe$Alpha\" \"a.b.c\")");

        // ---- name validation -------------------------------------------------------------------------
        String nullName = "none";
        try
        {
            new Alpha(null);
        }
        catch (NullPointerException e)
        {
            nullName = "NPE";
        }
        say("new Alpha(null)", nullName, "NPE");

        String emptyName = "none";
        try
        {
            new Alpha("");
        }
        catch (IllegalArgumentException e)
        {
            emptyName = "IAE";
        }
        say("new Alpha(\"\")", emptyName, "IAE");

        // ---- AllPermission ---------------------------------------------------------------------------
        AllPermission all = new AllPermission();
        say("AllPermission implies Alpha", all.implies(abc), Boolean.TRUE);
        say("Alpha implies AllPermission", abc.implies(all), Boolean.FALSE);

        // ---- a per-class PermissionCollection --------------------------------------------------------
        PermissionCollection pc = abStar.newPermissionCollection();
        pc.add(abStar);
        say("collection implies a.b.c", pc.implies(abc), Boolean.TRUE);
        say("collection implies a.bc", pc.implies(new Alpha("a.bc")), Boolean.FALSE);
        say("collection isReadOnly", pc.isReadOnly(), Boolean.FALSE);
        pc.setReadOnly();
        say("collection isReadOnly after", pc.isReadOnly(), Boolean.TRUE);

        // ---- Permissions: HETEROGENEOUS, so one class's grant must not answer another's --------------
        Permissions perms = new Permissions();
        perms.add(new Alpha("a.b.*"));
        perms.add(new Beta("q"));
        perms.add(new PropertyPermission("java.*", "read"));
        say("perms implies Alpha(a.b.c)", perms.implies(abc), Boolean.TRUE);
        say("perms implies Beta(a.b.c)", perms.implies(new Beta("a.b.c")), Boolean.FALSE);
        say("perms implies Beta(q)", perms.implies(new Beta("q")), Boolean.TRUE);
        say("perms implies prop read", perms.implies(new PropertyPermission("java.home", "read")), Boolean.TRUE);
        say("perms implies prop write", perms.implies(new PropertyPermission("java.home", "write")), Boolean.FALSE);

        int n = 0;
        Enumeration<Permission> e = perms.elements();
        while (e.hasMoreElements())
        {
            e.nextElement();
            n += 1;
        }
        say("perms elements", Integer.valueOf(n), Integer.valueOf(3));

        // ---- the ConcurrentHashMap LEGACY surface, which perms.elements() goes through ---------------
        // Found as a DENYLIST TRAP inside PropertyPermissionCollection.elements(): the CHM overlay declared
        // no elements(), and a member a name-winning overlay drops CEASES TO EXIST. Pinned here beside the
        // caller that found it.
        java.util.concurrent.ConcurrentHashMap<String, String> chm = new java.util.concurrent.ConcurrentHashMap<>();
        chm.put("k1", "v1");
        chm.put("k2", "v2");
        int keyN = 0;
        Enumeration<String> ke = chm.keys();
        while (ke.hasMoreElements())
        {
            ke.nextElement();
            keyN += 1;
        }
        say("chm keys()", Integer.valueOf(keyN), Integer.valueOf(2));

        String vals = "";
        Enumeration<String> ve = chm.elements();
        while (ve.hasMoreElements())
        {
            vals += ve.nextElement();
        }
        // Sorted-free check: both values must appear, in whatever order the snapshot gives.
        say("chm elements() sawV1", Boolean.valueOf(vals.indexOf("v1") >= 0), Boolean.TRUE);
        say("chm elements() sawV2", Boolean.valueOf(vals.indexOf("v2") >= 0), Boolean.TRUE);
        // contains() is the LEGACY spelling and tests a VALUE -- aliasing it to containsKey passes neither arm.
        say("chm contains(v1)", chm.contains("v1"), Boolean.TRUE);
        say("chm contains(k1)", chm.contains("k1"), Boolean.FALSE);
        say("chm mappingCount", Long.valueOf(chm.mappingCount()), Long.valueOf(2L));
        say("chm keySet size", Integer.valueOf(chm.keySet().size()), Integer.valueOf(2));
        String ksAdd = "none";
        try
        {
            chm.keySet().add("k3");
        }
        catch (UnsupportedOperationException ex)
        {
            ksAdd = "UOE";
        }
        say("chm keySet().add", ksAdd, "UOE");

        // ---- PropertyPermission actions (a Permission with real actions) -----------------------------
        PropertyPermission rw = new PropertyPermission("p", "read,write");
        say("prop actions", rw.getActions(), "read,write");
        say("prop implies read", rw.implies(new PropertyPermission("p", "read")), Boolean.TRUE);
        say("prop read implies rw", new PropertyPermission("p", "read").implies(rw), Boolean.FALSE);

        // ---- Principal -------------------------------------------------------------------------------
        Principal duke = new Named("duke");
        say("principal getName", duke.getName(), "duke");
        say("principal implies null", duke.implies(null), Boolean.FALSE);

        // ---- PrivilegedAction: the shape java.base uses everywhere ------------------------------------
        PrivilegedAction<String> pa = () -> "ran";
        say("PrivilegedAction.run", pa.run(), "ran");
        PrivilegedExceptionAction<String> pea = () -> "ran-ex";
        say("PrivilegedExceptionAction.run", pea.run(), "ran-ex");

        System.out.println("SecurityProbe done");
    }
}
