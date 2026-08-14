package demo;

import java.lang.reflect.Constructor;

import magic.Magic;

/**
 * Reflection arc M2 — reflective {@code Constructor.newInstance}. Resolves constructors via
 * {@code Class.getDeclaredConstructor} (matched by ARITY — parameter-type resolution is not yet implemented)
 * and instantiates: the NO-ARG constructor, and a two-arg constructor (int + reference). The {@code <init>}s
 * are run ONLY reflectively (no direct {@code new Widget(...)} warm-up), so they are compiled ON DEMAND when
 * {@code getDeclaredConstructor} resolves them. The reference field is printed with {@code String + Object}
 * concat (javac lowers it via {@code String.valueOf(Object)}) — exercised directly in the reflection closure
 * now that that path works.
 */
public class CtorReflectDemo
{
    public static void main(String[] args) throws Exception
    {
        String tag = "widget-label";
        Class<Widget> c = Widget.class;

        Constructor<Widget> defc = c.getDeclaredConstructor();                    // Widget()
        Widget w0 = defc.newInstance();
        Magic.printStr("no-arg size=" + w0.size + " label null=" + (w0.label == null ? 1 : 0) + "\n"); // 1, 1

        // Matched by ARITY only (two params), so the placeholder Class literals need not be the real types;
        // newInstance marshals each arg per the resolved <init>'s actual descriptor ('I' then 'L').
        Constructor<Widget> argc = c.getDeclaredConstructor(Object.class, Object.class); // Widget(int,Object)
        Widget w1 = argc.newInstance(Integer.valueOf(41), tag);
        Magic.printStr("two-arg size=" + w1.size + " label=" + w1.label
                + " (identity " + (w1.label == tag ? 1 : 0) + ")\n");             // 42, widget-label, 1

        Magic.printStr("ctor param counts=" + defc.getParameterCount()
                + " " + argc.getParameterCount() + "\n");                         // 0 2
    }
}

/** Reflective-instantiation fixture: a no-arg constructor (defaults) and a two-arg constructor (int + ref);
 *  the two-arg one bumps {@code size} by one to prove {@code <init>} actually ran. */
class Widget
{
    int size;
    Object label;

    Widget()
    {
        this.size = 1;
        this.label = null;
    }

    Widget(int s, Object l)
    {
        this.size = s + 1;
        this.label = l;
    }
}
