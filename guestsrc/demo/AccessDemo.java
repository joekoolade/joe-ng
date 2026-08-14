package demo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import magic.Magic;

/**
 * Reflection arc M2 — access enforcement (the security pillar). From this class (demo/AccessDemo) it reflects on
 * ANOTHER class (demo/Secret): a PUBLIC field/method is accessible; a PRIVATE one throws
 * {@code IllegalAccessException} — until {@code setAccessible(true)} is called. This is joe-ng's language-level
 * protection: reflection honours member access rather than freely bypassing it.
 */
public class AccessDemo
{
    public static void main(String[] args) throws Exception
    {
        Class<?> c = Secret.class;
        Secret s = new Secret();

        Field open = c.getDeclaredField("open");     // public int
        Field secret = c.getDeclaredField("secret"); // private int

        Magic.printStr("public field get=" + tryGet(open, s) + "\n");             // OK
        Magic.printStr("private field get (cross-class)=" + tryGet(secret, s) + "\n");   // IAE
        secret.setAccessible(true);
        Magic.printStr("private field get (setAccessible)=" + tryGet(secret, s) + "\n"); // OK

        Method pub = c.getDeclaredMethod("publicSum");   // public int publicSum(int)
        Method prv = c.getDeclaredMethod("privateSum");  // private int privateSum(int)

        Magic.printStr("public method invoke=" + tryInvoke(pub, s) + "\n");         // OK
        Magic.printStr("private method invoke (cross-class)=" + tryInvoke(prv, s) + "\n"); // IAE
        prv.setAccessible(true);
        Magic.printStr("private method invoke (setAccessible)=" + tryInvoke(prv, s) + "\n"); // OK
    }

    static String tryGet(Field f, Object o)
    {
        try
        {
            f.get(o);
            return "OK";
        }
        catch (IllegalAccessException e)
        {
            return "IAE";
        }
    }

    static String tryInvoke(Method m, Object o)
    {
        try
        {
            m.invoke(o, Integer.valueOf(3));
            return "OK";
        }
        catch (IllegalAccessException e)
        {
            return "IAE";
        }
        catch (Exception e)
        {
            return "ERR";
        }
    }
}

/** Target with public + private members, reflected on from the (different) AccessDemo class. */
class Secret
{
    public int open = 1;
    private int secret = 42;

    public int publicSum(int x)
    {
        return x + open;
    }

    private int privateSum(int x)
    {
        return x + secret;
    }
}
