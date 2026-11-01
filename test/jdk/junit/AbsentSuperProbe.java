/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-01
 */

import java.util.Base64;

/**
 * A class whose SUPERCLASS can never load. A JVM refuses it with NoClassDefFoundError naming the superclass; joe-ng
 * used to register it anyway, skip the absent superclass's constructor and take a WILD BRANCH on the next call
 * through it (the https URL handler, whose javax/net/ssl superclass the image does not ship). The probe makes one
 * from its own nested class: it reads Sub's bytes, renames the class (and, in the absent arm, its superclass) to a
 * same-length name, and defines it. A JVM throws at defineClass and joe-ng at first use, so each arm defines AND
 * uses inside one report. Each arm runs on its own and prints what it got.
 */
public class AbsentSuperProbe
{
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
            got = "threw " + t.getClass().getName() + " " + t.getMessage();
        }
        System.out.println(what + ": " + got);
    }

    public static class Base
    {
        public String who()
        {
            return "base";
        }
    }

    public static class Sub extends Base
    {
        public Sub()
        {
        }

        public String who()
        {
            return "sub of " + super.who();
        }
    }

    public interface Face
    {
        String face();
    }

    public static class Impl implements Face
    {
        public Impl()
        {
        }

        public String face()
        {
            return "impl";
        }
    }

    static class Definer extends ClassLoader
    {
        Definer()
        {
            super(AbsentSuperProbe.class.getClassLoader());
        }

        Class<?> define(String name, byte[] b)
        {
            return defineClass(name, b, 0, b.length);
        }
    }

    /**
     * Sub's own classfile, as javac 26 compiled it above, embedded because a probe's classes are baked into the image
     * and are not readable as resources there. Regenerate if Sub or Base changes:
     * {@code base64 -i 'AbsentSuperProbe$Sub.class'}.
     */
    static final String SUB_CLASS =
              "yv66vgAAAEYAKQoAAgADBwAEDAAFAAYBABVBYnNlbnRTdXBlclByb2JlJEJhc2UBAAY8aW5pdD4BAAMoKVYKAAIACAwACQAKAQAD"
            + "d2hvAQAUKClMamF2YS9sYW5nL1N0cmluZzsSAAAADAwADQAOAQAXbWFrZUNvbmNhdFdpdGhDb25zdGFudHMBACYoTGphdmEvbGFu"
            + "Zy9TdHJpbmc7KUxqYXZhL2xhbmcvU3RyaW5nOwcAEAEAFEFic2VudFN1cGVyUHJvYmUkU3ViAQAEQ29kZQEAD0xpbmVOdW1iZXJU"
            + "YWJsZQEAClNvdXJjZUZpbGUBABVBYnNlbnRTdXBlclByb2JlLmphdmEBAAhOZXN0SG9zdAcAFwEAEEFic2VudFN1cGVyUHJvYmUB"
            + "ABBCb290c3RyYXBNZXRob2RzCAAaAQAIc3ViIG9mIAEPBgAcCgAdAB4HAB8MAA0AIAEAJGphdmEvbGFuZy9pbnZva2UvU3RyaW5n"
            + "Q29uY2F0RmFjdG9yeQEAmChMamF2YS9sYW5nL2ludm9rZS9NZXRob2RIYW5kbGVzJExvb2t1cDtMamF2YS9sYW5nL1N0cmluZztM"
            + "amF2YS9sYW5nL2ludm9rZS9NZXRob2RUeXBlO0xqYXZhL2xhbmcvU3RyaW5nO1tMamF2YS9sYW5nL09iamVjdDspTGphdmEvbGFu"
            + "Zy9pbnZva2UvQ2FsbFNpdGU7AQAMSW5uZXJDbGFzc2VzAQAEQmFzZQEAA1N1YgcAJQEAJWphdmEvbGFuZy9pbnZva2UvTWV0aG9k"
            + "SGFuZGxlcyRMb29rdXAHACcBAB5qYXZhL2xhbmcvaW52b2tlL01ldGhvZEhhbmRsZXMBAAZMb29rdXAAIQAPAAIAAAAAAAIAAQAF"
            + "AAYAAQARAAAAIQABAAEAAAAFKrcAAbEAAAABABIAAAAKAAIAAAA3AAQAOAABAAkACgABABEAAAAiAAEAAQAAAAoqtwAHugALAACw"
            + "AAAAAQASAAAABgABAAAAPAAEABMAAAACABQAFQAAAAIAFgAYAAAACAABABsAAQAZACEAAAAaAAMAAgAWACIACQAPABYAIwAJACQA"
            + "JgAoABk=";

    static byte[] subBytes()
    {
        return Base64.getDecoder().decode(SUB_CLASS);
    }

    /** Impl's own classfile, embedded for the same reason as Sub's. Regenerate if Impl or Face changes. */
    static final String IMPL_CLASS =
              "yv66vgAAAEYAGQoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCAAIAQAEaW1wbAcACgEAFUFi"
            + "c2VudFN1cGVyUHJvYmUkSW1wbAcADAEAFUFic2VudFN1cGVyUHJvYmUkRmFjZQEABENvZGUBAA9MaW5lTnVtYmVyVGFibGUBAARm"
            + "YWNlAQAUKClMamF2YS9sYW5nL1N0cmluZzsBAApTb3VyY2VGaWxlAQAVQWJzZW50U3VwZXJQcm9iZS5qYXZhAQAITmVzdEhvc3QH"
            + "ABUBABBBYnNlbnRTdXBlclByb2JlAQAMSW5uZXJDbGFzc2VzAQAESW1wbAEABEZhY2UAIQAJAAIAAQALAAAAAgABAAUABgABAA0A"
            + "AAAhAAEAAQAAAAUqtwABsQAAAAEADgAAAAoAAgAAAEgABABJAAEADwAQAAEADQAAABsAAQABAAAAAxIHsAAAAAEADgAAAAYAAQAA"
            + "AE0AAwARAAAAAgASABMAAAACABQAFgAAABIAAgAJABQAFwAJAAsAFAAYBgk=";

    /** Replace every occurrence of {@code from} with {@code to} (same length, so the constant pool stays valid). */
    static byte[] patch(byte[] b, String from, String to)
    {
        byte[] f = from.getBytes();
        byte[] t = to.getBytes();
        byte[] out = b.clone();
        for (int i = 0; i + f.length <= out.length; i++)
        {
            boolean match = true;
            for (int j = 0; j < f.length && match; j++)
            {
                match = out[i + j] == f[j];
            }
            if (match)
            {
                System.arraycopy(t, 0, out, i, t.length);
            }
        }
        return out;
    }

    public static void main(String[] args)
    {
        report("present superclass", () ->
        {
            byte[] b = patch(subBytes(), "AbsentSuperProbe$Sub", "AbsentSuperProbe$Suc");
            Class<?> c = new Definer().define("AbsentSuperProbe$Suc", b);
            Object o = c.getDeclaredConstructor().newInstance();
            return c.getSuperclass().getName() + " " + ((Base) o).who();
        });
        report("absent superclass", () ->
        {
            byte[] b = patch(patch(subBytes(), "AbsentSuperProbe$Sub", "AbsentSuperProbe$Sud"),
                    "AbsentSuperProbe$Base", "AbsentSuperProbe$Gone");
            Class<?> c = new Definer().define("AbsentSuperProbe$Sud", b);
            Object o = c.getDeclaredConstructor().newInstance();
            return "constructed " + o.getClass().getName();
        });
        report("absent superclass, forName", () ->
        {
            byte[] b = patch(patch(subBytes(), "AbsentSuperProbe$Sub", "AbsentSuperProbe$Sue"),
                    "AbsentSuperProbe$Base", "AbsentSuperProbe$Gone");
            Definer d = new Definer();
            d.define("AbsentSuperProbe$Sue", b);
            return Class.forName("AbsentSuperProbe$Sue", false, d).getName();
        });
        report("present interface", () ->
        {
            byte[] b = patch(Base64.getDecoder().decode(IMPL_CLASS), "AbsentSuperProbe$Impl", "AbsentSuperProbe$Impm");
            Class<?> c = new Definer().define("AbsentSuperProbe$Impm", b);
            Object o = c.getDeclaredConstructor().newInstance();
            return c.getInterfaces()[0].getName() + " " + ((Face) o).face();
        });
        report("absent interface", () ->
        {
            byte[] b = patch(patch(Base64.getDecoder().decode(IMPL_CLASS), "AbsentSuperProbe$Impl",
                    "AbsentSuperProbe$Impn"), "AbsentSuperProbe$Face", "AbsentSuperProbe$Gone");
            Class<?> c = new Definer().define("AbsentSuperProbe$Impn", b);
            Object o = c.getDeclaredConstructor().newInstance();
            return "constructed " + o.getClass().getName() + " interfaces=" + c.getInterfaces().length;
        });
        report("absent interface, forName", () ->
        {
            byte[] b = patch(patch(Base64.getDecoder().decode(IMPL_CLASS), "AbsentSuperProbe$Impl",
                    "AbsentSuperProbe$Impo"), "AbsentSuperProbe$Face", "AbsentSuperProbe$Gone");
            Definer d = new Definer();
            d.define("AbsentSuperProbe$Impo", b);
            return Class.forName("AbsentSuperProbe$Impo", false, d).getName();
        });
        System.out.println("AbsentSuperProbe done");
    }
}
