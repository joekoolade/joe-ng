package demo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import magic.Magic;

/**
 * Reflection arc M3 -- {@code ClassLoader.defineClass(byte[])}. The class {@code plugin/Plugin} is NOT in the
 * loader's classDir; its ONLY source is the raw classfile {@code byte[]} embedded below (compiled offline). The
 * demo hands those bytes to a {@code ClassLoader} subclass's {@code defineClass}, which materializes a live,
 * runnable class on the metal, then drives it entirely through M2 reflection: {@code getDeclaredConstructor()
 * .newInstance()} builds an instance (running its {@code <init>}, which sets {@code base=40}), and
 * {@code getDeclaredMethod("answer").invoke(p, 2)} runs its method ({@code base + 2}) -> {@code 42}. Proving
 * the bytes -> Class -> instance -> method path works for a class the VM never saw at image-build time.
 */
public class DefineClassDemo
{
    // plugin/Plugin.class (<built-in function len> bytes), compiled offline with JDK 26. base=40; answer(x) returns base + x.
    private static final byte[] PLUGIN =
    {
        -54,-2,-70,-66,0,0,0,70,0,19,10,0,2,0,3,7,0,4,12,0,
        5,0,6,1,0,16,106,97,118,97,47,108,97,110,103,47,79,98,106,101,
        99,116,1,0,6,60,105,110,105,116,62,1,0,3,40,41,86,9,0,8,
        0,9,7,0,10,12,0,11,0,12,1,0,13,112,108,117,103,105,110,47,
        80,108,117,103,105,110,1,0,4,98,97,115,101,1,0,1,73,1,0,4,
        67,111,100,101,1,0,15,76,105,110,101,78,117,109,98,101,114,84,97,98,
        108,101,1,0,6,97,110,115,119,101,114,1,0,4,40,73,41,73,1,0,
        10,83,111,117,114,99,101,70,105,108,101,1,0,11,80,108,117,103,105,110,
        46,106,97,118,97,0,33,0,8,0,2,0,0,0,1,0,2,0,11,0,
        12,0,0,0,2,0,1,0,5,0,6,0,1,0,13,0,0,0,39,0,
        2,0,1,0,0,0,11,42,-73,0,1,42,16,40,-75,0,7,-79,0,0,
        0,1,0,14,0,0,0,10,0,2,0,0,0,5,0,4,0,7,0,1,
        0,15,0,16,0,1,0,13,0,0,0,31,0,2,0,2,0,0,0,7,
        42,-76,0,7,27,96,-84,0,0,0,1,0,14,0,0,0,6,0,1,0,
        0,0,11,0,1,0,17,0,0,0,2,0,18
    };

    public static void main(String[] args) throws Exception
    {
        AppLoader loader = new AppLoader();
        Class<?> c = loader.define(PLUGIN);
        Magic.printStr("defined name=" + c.getName() + "\n");             // plugin.Plugin

        Constructor<?> ctor = c.getDeclaredConstructor();
        Object p = ctor.newInstance();
        Magic.printStr("instantiated null=" + (p == null ? 1 : 0) + "\n"); // 0

        Method answer = c.getDeclaredMethod("answer");                     // int answer(int)
        Object r = answer.invoke(p, Integer.valueOf(2));
        Magic.printStr("plugin answer(2)=" + ((Integer) r).intValue() + "\n"); // 42
    }
}

/** A minimal application loader: subclassing {@code ClassLoader} is how a program reaches the protected
 *  {@code defineClass} (exactly the custom-loader shape M3 targets). */
class AppLoader extends ClassLoader
{
    Class<?> define(byte[] b)
    {
        return defineClass(null, b, 0, b.length);
    }
}
