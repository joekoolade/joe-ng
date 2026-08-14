package demo;

import java.lang.reflect.Modifier;

import magic.Magic;

/**
 * Reflection arc M1 — Class.forName + incremental load + the Class query surface + field-lookup access filtering.
 * Runs from a real {@code main(String[])} launched from the RAMFS manifest.
 *
 * <p>Field-lookup filtering runs FIRST (a throw-heavy sequence, kept ahead of the forName incremental pulls —
 * see the known layout-sensitive incremental-load corruption noted in PLAN.md): {@code getField} returns only
 * PUBLIC fields (NoSuchFieldException otherwise), {@code getDeclaredField} returns any-access.
 *
 * <p>Then {@code Class.forName}: (1) an already-loaded class returns its cached mirror ({@code == X.class});
 * (2) {@code "demo.Plugin"} — named only by string, never preloaded — is pulled into the live program WITHOUT
 * wiping it, running its {@code <clinit>} (proving demand-load-after-launch); (3) a '/'-name and (4) an absent
 * class each throw ClassNotFoundException. Finally the query surface: getModifiers (ACC_SUPER stripped),
 * isInterface, getSimpleName/getPackageName.
 */
public class ForNameDemo
{
    public static void main(String[] args) throws Exception
    {
        // ---- Class.forName + incremental load ----
        Magic.printStr("forName: start\n");
        Class<?> ic = Class.forName("java.lang.Integer");
        Magic.printStr("forName(Integer)=" + ic.getName()
                + " identity=" + (ic == Integer.class ? 1 : 0) + "\n");   // java.lang.Integer identity=1

        Class<?> pc = Class.forName("demo.Plugin");                       // Plugin.<clinit> prints here (first load)
        Magic.printStr("forName(Plugin)=" + pc.getName() + "\n");         // demo.Plugin

        Class<?> pc2 = Class.forName("demo.Plugin");                      // second time: cached, NO re-run of <clinit>
        Magic.printStr("forName(Plugin) again identity=" + (pc == pc2 ? 1 : 0) + "\n");   // 1

        try
        {
            Class.forName("java/lang/Object");                           // '/' is not a valid binary name
            Magic.printStr("slash: NO throw (BAD)\n");
        }
        catch (ClassNotFoundException e)
        {
            Magic.printStr("slash: CNFE ok\n");
        }

        try
        {
            Class.forName("demo.DoesNotExist");                          // not embedded
            Magic.printStr("absent: NO throw (BAD)\n");
        }
        catch (ClassNotFoundException e)
        {
            Magic.printStr("absent: CNFE ok\n");
        }
        Magic.printStr("forName: done\n");

        // ---- query surface: getModifiers / Modifier / isInterface / simple+package name ----
        Magic.printStr("query: start\n");
        Magic.printStr("Object.getModifiers=" + Modifier.toString(Object.class.getModifiers()) + "\n");   // public

        boolean sync = Modifier.isSynchronized(ForNameDemo.class.getModifiers());   // StripACC_SUPER: must be false
        Magic.printStr("ForNameDemo synchronized=" + (sync ? 1 : 0) + " (want 0)\n");

        Magic.printStr("Runnable.isInterface=" + (Runnable.class.isInterface() ? 1 : 0)
                + " ForNameDemo.isInterface=" + (ForNameDemo.class.isInterface() ? 1 : 0) + "\n");   // 1 0

        Magic.printStr("Integer simple=" + Integer.class.getSimpleName()
                + " pkg=" + Integer.class.getPackageName() + "\n");   // Integer java.lang
        Magic.printStr("query: done\n");

        // ---- field-lookup filtering LAST (natural order — this used to crash from the exception-unwind bug) ----
        Magic.printStr("fields: start\n");
        Fields fo = new Fields();
        Class<?> fc = fo.getClass();
        Magic.printStr("getField pub=" + tryGetField(fc, "pub")           // OK (public)
                + " pkg=" + tryGetField(fc, "pkg")                        // NSFE (package-private)
                + " priv=" + tryGetField(fc, "priv")                      // NSFE (private)
                + " absent=" + tryGetField(fc, "absent")                  // NSFE (no such field)
                + " null=" + tryGetField(fc, null) + "\n");               // NPE
        Magic.printStr("getDeclaredField priv=" + tryGetDeclared(fc, "priv")   // OK (any access)
                + " absent=" + tryGetDeclared(fc, "absent") + "\n");           // NSFE
        Magic.printStr("fields: done\n");
    }

    /** getField outcome as a short tag (OK/NSFE/NPE) — mirrors the JDK getField/Exceptions public-only rule. */
    static String tryGetField(Class<?> c, String name)
    {
        try
        {
            c.getField(name);
            return "OK";
        }
        catch (NoSuchFieldException e)
        {
            return "NSFE";
        }
        catch (NullPointerException e)
        {
            return "NPE";
        }
    }

    static String tryGetDeclared(Class<?> c, String name)
    {
        try
        {
            c.getDeclaredField(name);
            return "OK";
        }
        catch (NoSuchFieldException e)
        {
            return "NSFE";
        }
    }
}

/** A class named only via {@code Class.forName("demo.Plugin")} — never as a class literal or {@code new}, so
 *  reachability analysis does NOT preload it. Its {@code <clinit>} side-effect (a print) is the proof that the
 *  incremental load actually ran the class on the live program. */
class Plugin
{
    static int marker;

    static
    {
        marker = 42;
        Magic.printStr("  Plugin.<clinit> ran, marker=" + marker + "\n");
    }
}

/** Fixture with fields of every access level — {@code getField} must return only {@code pub}; {@code getDeclaredField}
 *  returns any (like the JDK getField/Exceptions test). */
class Fields
{
    public int pub;
    int pkg;
    private int priv;
}
