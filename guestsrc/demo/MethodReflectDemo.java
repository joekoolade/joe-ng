package demo;

import java.lang.reflect.Method;

import magic.Magic;

/**
 * Reflection arc M2 — reflective {@code Method.invoke}. Resolves methods via {@code Class.getDeclaredMethod}
 * (by name; overload resolution by parameter types is not yet implemented) and invokes them: a STATIC method
 * with two int args, an INSTANCE method with one int arg reading an instance field, an instance method
 * returning a reference, and a {@code void} method. Verifies via unboxing/identity (avoids {@code String +
 * Object} concat, which lowers to the still-unfixed {@code String.valueOf(Object)} JIT bug).
 */
public class MethodReflectDemo
{
    public static void main(String[] args) throws Exception
    {
        String tag = "the-message";
        Target t = new Target();
        t.factor = 10;
        t.msg = tag;
        Class<?> c = Target.class;

        // No warm-up: the target methods are invoked ONLY reflectively, so they are compiled ON DEMAND when
        // getDeclaredMethod resolves them.
        Method add = c.getDeclaredMethod("add");                 // static int add(int,int)
        Object r1 = add.invoke(null, Integer.valueOf(3), Integer.valueOf(4));
        Magic.printStr("add(3,4)=" + ((Integer) r1).intValue() + "\n");                 // 7

        Method scale = c.getDeclaredMethod("scale");             // int scale(int) instance (reads factor=10)
        Object r2 = scale.invoke(t, Integer.valueOf(5));
        Magic.printStr("scale(5)=" + ((Integer) r2).intValue() + "\n");                 // 50

        Method getMsg = c.getDeclaredMethod("getMsg");           // Object getMsg() instance -> t.msg
        Object r3 = getMsg.invoke(t);
        Magic.printStr("getMsg identity=" + (r3 == tag ? 1 : 0) + "\n");                // 1

        Method noop = c.getDeclaredMethod("noop");               // void noop()
        Object r4 = noop.invoke(t);
        Magic.printStr("noop returned null=" + (r4 == null ? 1 : 0) + "\n");            // 1

        Magic.printStr("method count=" + add.getParameterCount()
                + " scale params=" + scale.getParameterCount() + "\n");                // 2 1
    }
}

/** Reflective-invocation fixture: a static 2-arg method, an instance 1-arg method, a reference-returning
 *  method, and a void method. */
class Target
{
    int factor;
    Object msg;

    static int add(int a, int b)
    {
        return a + b;
    }

    int scale(int x)
    {
        return x * factor;
    }

    Object getMsg()
    {
        return msg;
    }

    void noop()
    {
    }
}
