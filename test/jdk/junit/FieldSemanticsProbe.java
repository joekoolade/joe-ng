/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-15
 */
import java.lang.reflect.Field;

/**
 * What reflective field access checks AROUND the read or write. The target is a separate top-level class, so no
 * nestmate rule applies. Arms:
 * <ul>
 *   <li>access: every accessor on a private field of another class -- {@code get}, {@code set} AND the typed
 *       ones -- refuses until {@code setAccessible(true)};</li>
 *   <li>final: a final instance field refuses a write until {@code setAccessible(true)}, a static final refuses it
 *       even then, and a typed setter is held to the same rule;</li>
 *   <li>receiver: null for an instance field, an object of an UNRELATED class that has a same-named field, and a
 *       SUBCLASS that declares its own same-named field -- the declared field is the one read and written;</li>
 *   <li>{@code set}: unboxing with widening, and refusal of a narrowing, a null for a primitive and a wrong
 *       reference type;</li>
 *   <li>typed getters and setters: widening allowed, narrowing refused, and a primitive accessor on a reference
 *       field (boxed or not) refused rather than reading or writing the pointer's bits.</li>
 * </ul>
 * Each refusal prints the exception's class only; each write is read back. Prints values only; the host diff is
 * the gate.
 */
public class FieldSemanticsProbe
{
    interface Arm
    {
        Object run() throws Throwable;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            Object r = a.run();
            System.out.println(what + " -> " + (r == null ? "null" : r.getClass().getSimpleName() + " " + r));
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> " + t.getClass().getName());
        }
    }

    static Field f(String name) throws NoSuchFieldException
    {
        return FieldTarget.class.getDeclaredField(name);
    }

    public static void main(String[] args) throws Exception
    {
        FieldTarget t = new FieldTarget();

        System.out.println("-- access: a private field of another class --");
        Field secret = f("secret");
        arm("get", () -> secret.get(t));
        arm("getInt", () -> secret.getInt(t));
        arm("getLong", () -> secret.getLong(t));
        arm("set", () ->
        {
            secret.set(t, 5);
            return t.peekSecret();
        });
        arm("setInt", () ->
        {
            secret.setInt(t, 6);
            return t.peekSecret();
        });
        arm("canAccess before", () -> secret.canAccess(t));
        secret.setAccessible(true);
        arm("canAccess after", () -> secret.canAccess(t));
        arm("getInt after", () -> secret.getInt(t));
        arm("setInt after", () ->
        {
            secret.setInt(t, 8);
            return t.peekSecret();
        });

        Field num0 = f("num");
        Field wide0 = f("wide");
        arm("canAccess(null) on instance", () -> num0.canAccess(null));
        arm("canAccess(unrelated) on instance", () -> num0.canAccess(new Unrelated()));
        arm("canAccess(t) on static", () -> f("stat").canAccess(t));
        Field secret2 = f("secret");
        arm("trySetAccessible", () -> secret2.trySetAccessible());
        arm("getInt after trySetAccessible", () -> secret2.getInt(t));
        Field hidden = FieldTarget.class.getDeclaredField("hidden");
        Field hidden2 = FieldTarget.class.getDeclaredField("hidden2");
        arm("array setAccessible", () ->
        {
            java.lang.reflect.AccessibleObject.setAccessible(new java.lang.reflect.AccessibleObject[] { hidden,
                    hidden2 }, true);
            return hidden.getInt(t) + hidden2.getInt(t);
        });

        System.out.println("-- final --");
        Field fin = f("fin");
        arm("set final", () ->
        {
            fin.set(t, 2);
            return t.fin;
        });
        arm("setInt final", () ->
        {
            fin.setInt(t, 3);
            return t.fin;
        });
        arm("get final", () -> fin.get(t));
        fin.setAccessible(true);
        arm("set final accessible", () ->
        {
            fin.set(t, 4);
            return fin.get(t);
        });
        arm("setInt final accessible", () ->
        {
            fin.setInt(t, 5);
            return fin.getInt(t);
        });
        Field sfin = f("SFIN");
        arm("set static final", () ->
        {
            sfin.set(null, "changed");
            return sfin.get(null);
        });
        sfin.setAccessible(true);
        arm("set static final accessible", () ->
        {
            sfin.set(null, "changed");
            return sfin.get(null);
        });
        arm("get static final", () -> sfin.get(null));

        System.out.println("-- receiver --");
        Field num = f("num");
        Field stat = f("stat");
        arm("get null receiver", () -> num.get(null));
        arm("getInt null receiver", () -> num.getInt(null));
        arm("get unrelated receiver", () -> num.get(new Unrelated()));
        arm("set unrelated receiver", () ->
        {
            Unrelated u = new Unrelated();
            num.set(u, 99);
            return u.num;
        });
        arm("setInt unrelated receiver", () ->
        {
            Unrelated u = new Unrelated();
            num.setInt(u, 98);
            return u.num;
        });
        arm("get String receiver", () -> num.get("x"));
        arm("get subclass that shadows", () -> num.get(new Shadow()));
        arm("setInt subclass that shadows", () ->
        {
            Shadow sh = new Shadow();
            num.setInt(sh, 21);
            return sh.num + "/" + ((FieldTarget) sh).num;
        });
        arm("static get with receiver", () -> stat.get("ignored"));
        arm("static get null", () -> stat.get(null));

        System.out.println("-- set: unbox and widen, or refuse --");
        Field wide = f("wide");
        Field str = f("str");
        Field obj = f("obj");
        Field dbl = f("dbl");
        arm("long <- Integer", () ->
        {
            wide.set(t, 7);
            return t.wide;
        });
        arm("long <- Character", () ->
        {
            wide.set(t, 'A');
            return t.wide;
        });
        arm("int <- Long", () ->
        {
            num.set(t, 7L);
            return t.num;
        });
        arm("int <- Short", () ->
        {
            num.set(t, (short) -4);
            return t.num;
        });
        arm("int <- null", () ->
        {
            num.set(t, null);
            return t.num;
        });
        arm("int <- String", () ->
        {
            num.set(t, "1");
            return t.num;
        });
        arm("double <- Float", () ->
        {
            dbl.set(t, 1.5f);
            return t.dbl;
        });
        arm("double <- Long", () ->
        {
            dbl.set(t, 1L << 50);
            return t.dbl;
        });
        arm("String <- Integer", () ->
        {
            str.set(t, 12);
            return t.str;
        });
        arm("String <- String", () ->
        {
            str.set(t, "ok");
            return t.str;
        });
        arm("String <- null", () ->
        {
            str.set(t, null);
            return t.str;
        });
        arm("Object <- Integer", () ->
        {
            obj.set(t, 3);
            return t.obj;
        });

        System.out.println("-- typed getters --");
        Field b = f("b");
        Field c = f("c");
        Field box = f("box");
        Field flag = f("flag");
        arm("getLong(int)", () -> num.getLong(t));
        arm("getInt(long)", () -> wide.getInt(t));
        arm("getInt(String)", () -> str.getInt(t));
        arm("getInt(Integer)", () -> box.getInt(t));
        arm("getBoolean(int)", () -> num.getBoolean(t));
        arm("getInt(boolean)", () -> flag.getInt(t));
        arm("getShort(byte)", () -> b.getShort(t));
        arm("getChar(byte)", () -> b.getChar(t));
        arm("getInt(char)", () -> c.getInt(t));
        arm("getShort(char)", () -> c.getShort(t));
        arm("getDouble(long)", () -> wide.getDouble(t));
        arm("getFloat(double)", () -> dbl.getFloat(t));
        arm("get(byte)", () -> b.get(t));
        arm("get(char)", () -> c.get(t));
        arm("get(boolean)", () -> flag.get(t));
        arm("get(Integer)", () -> box.get(t));

        System.out.println("-- typed setters --");
        arm("setInt(long)", () ->
        {
            wide.setInt(t, -3);
            return t.wide;
        });
        arm("setLong(int)", () ->
        {
            num.setLong(t, 1L);
            return t.num;
        });
        arm("setInt(Integer)", () ->
        {
            box.setInt(t, 4);
            return t.box;
        });
        arm("setInt(String)", () ->
        {
            str.setInt(t, 4);
            return t.str;
        });
        arm("setLong(Object)", () ->
        {
            obj.setLong(t, 4L);
            return t.obj;
        });
        arm("setByte(short)", () ->
        {
            Field s = f("s");
            s.setByte(t, (byte) -2);
            return t.s;
        });
        arm("setChar(short)", () ->
        {
            Field s = f("s");
            s.setChar(t, 'q');
            return t.s;
        });
        arm("setChar(int)", () ->
        {
            num.setChar(t, 'q');
            return t.num;
        });
        arm("setShort(char)", () ->
        {
            c.setShort(t, (short) 1);
            return t.c;
        });
        arm("setBoolean(int)", () ->
        {
            num.setBoolean(t, true);
            return t.num;
        });
        arm("setBoolean(boolean)", () ->
        {
            flag.setBoolean(t, false);
            return t.flag;
        });
        arm("setFloat(long)", () ->
        {
            wide.setFloat(t, 1f);
            return t.wide;
        });
        arm("setLong(double)", () ->
        {
            dbl.setLong(t, 9L);
            return t.dbl;
        });
        System.out.println("str still a String: " + (t.str == null || t.str instanceof String));
        System.out.println("FieldSemanticsProbe done");
    }
}

class FieldTarget
{
    private int secret = 1;
    private int hidden = 2;
    private int hidden2 = 3;
    final int fin;
    static final String SFIN = new String("orig");
    static int stat = 11;
    int num = 10;
    long wide = 20L;
    double dbl = 0.5;
    byte b = -5;
    short s = 300;
    char c = 'z';
    boolean flag = true;
    String str = "s";
    Object obj = "o";
    Integer box = 40;

    FieldTarget()
    {
        fin = 1;
    }

    int peekSecret()
    {
        return secret;
    }
}

class Shadow extends FieldTarget
{
    int num = 77;
}

class Unrelated
{
    int num = 50;
}
