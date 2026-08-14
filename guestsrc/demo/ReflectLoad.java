package demo;

import java.io.FileInputStream;
import java.lang.reflect.Method;

import magic.Magic;

/**
 * Reflection arc M4 — the end-to-end "load a program from a file and run it" milestone. The class
 * {@code plugin/FilePlugin} lives ONLY as a file in the embedded RAMFS ({@code /plugins/plugin/FilePlugin.class},
 * compiled into {@code ramfs/} but NOT the loader's classDir), so it is unreachable by {@code forName}. This demo
 * reads its raw bytes off the file system ({@code FileInputStream.readAllBytes}), hands them to
 * {@code ClassLoader.defineClass} (M3), and then drives the freshly-defined class purely through M2 reflection:
 * {@code getDeclaredConstructor().newInstance()} (runs its {@code <init>}, {@code seed=100}) +
 * {@code getDeclaredMethod("scale").invoke(p, 7)} ({@code seed + 7*3}) -> {@code 121}. File bytes -> Class ->
 * instance -> method, for a class the VM never saw at image-build time. Verified on QEMU and a real Pi 4.
 */
public class ReflectLoad
{
    public static void main(String[] args) throws Exception
    {
        FileInputStream in = new FileInputStream("/plugins/plugin/FilePlugin.class");
        byte[] bytes = in.readAllBytes();
        in.close();
        Magic.printStr("read " + bytes.length + " bytes from /plugins/plugin/FilePlugin.class\n");

        PluginLoader loader = new PluginLoader();
        Class<?> c = loader.define(bytes);
        Magic.printStr("defined " + c.getName() + "\n");                  // plugin.FilePlugin

        Object p = c.getDeclaredConstructor().newInstance();             // runs <init> (seed=100), on-demand compiled
        Method scale = c.getDeclaredMethod("scale");                     // int scale(int)
        Object r = scale.invoke(p, Integer.valueOf(7));
        Magic.printStr("scale(7)=" + ((Integer) r).intValue() + "\n");   // 121
    }
}

/** A minimal file-backed application loader: subclassing {@code ClassLoader} reaches the protected
 *  {@code defineClass} (the custom-loader shape). Distinct from M3's {@code AppLoader} in this same package. */
class PluginLoader extends ClassLoader
{
    Class<?> define(byte[] b)
    {
        return defineClass(null, b, 0, b.length);
    }
}
