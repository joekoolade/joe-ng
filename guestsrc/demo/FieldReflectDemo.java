package demo;

import java.lang.reflect.Field;

import magic.Magic;

/**
 * Reflection arc M2 — reflective {@code Field.get}/{@code set}. Reads and writes an object's int/long/boolean/
 * reference fields through {@code Field} objects obtained from {@code Class.getDeclaredField}, verifying the
 * boxed {@code get}/{@code set}, the typed {@code getInt}/{@code setInt}/... accessors, and that a reference
 * {@code get} returns the SAME identity (via the new {@code Magic.fromAddr} long->Object reinterpret).
 *
 * <p>A reference {@code get} result is printed with {@code String + Object} concat (which javac lowers via
 * {@code String.valueOf(Object)} -> {@code toString}) — exercised directly in the reflection closure now that
 * that path works.
 */
public class FieldReflectDemo
{
    public static void main(String[] args) throws Exception
    {
        String hi = "hi";
        String bye = "bye";
        Holder h = new Holder();
        h.i = 10;
        h.j = 20L;
        h.flag = true;
        h.ref = hi;
        Class<?> c = h.getClass();
        Field fi = c.getDeclaredField("i");
        Field fj = c.getDeclaredField("j");
        Field ff = c.getDeclaredField("flag");
        Field fr = c.getDeclaredField("ref");

        // ---- boxed get + reference get printed via String.valueOf(Object) concat ----
        Object gi = fi.get(h);
        Object gr = fr.get(h);
        Magic.printStr("boxed get: i==10 " + (((Integer) gi).intValue() == 10 ? 1 : 0)
                + " ref=" + gr + " (==hi " + (gr == hi ? 1 : 0) + ")\n");

        // ---- typed get ----
        Magic.printStr("typed get: getInt=" + fi.getInt(h) + " getLong=" + fj.getLong(h)
                + " getBoolean=" + (ff.getBoolean(h) ? 1 : 0) + "\n");

        // ---- typed set ----
        fi.setInt(h, 42);
        fj.setLong(h, 99L);
        ff.setBoolean(h, false);
        fr.set(h, bye);
        Magic.printStr("after set: i=" + h.i + " j=" + h.j
                + " flag=" + (h.flag ? 1 : 0) + " ref=" + h.ref + " (==bye " + (h.ref == bye ? 1 : 0) + ")\n");

        // ---- boxed set (unbox) ----
        fi.set(h, Integer.valueOf(7));
        Magic.printStr("boxed set: i=" + h.i + "\n");
    }
}

/** Fixture with one field of each primitive/reference kind exercised by the reflective get/set. */
class Holder
{
    int i;
    long j;
    boolean flag;
    Object ref;
}
