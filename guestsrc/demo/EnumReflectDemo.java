package demo;

import magic.Magic;

/**
 * Reflection arc M4 — enum reflection. {@code Class.isEnum()} (ACC_ENUM) and {@code Class.getEnumConstants()}
 * (the enum's synthesised {@code values()}, reached through M2 reflection) on a real enum, plus the negative
 * case (a non-enum class reports {@code isEnum()==false}, {@code getEnumConstants()==null}).
 */
public class EnumReflectDemo
{
    enum Planet { MERCURY, VENUS, EARTH }

    public static void main(String[] args) throws Exception
    {
        Class<?> c = Planet.class;
        Magic.printStr("isEnum=" + (c.isEnum() ? 1 : 0) + "\n");           // 1

        Object[] cs = c.getEnumConstants();
        Magic.printStr("constants=" + cs.length + "\n");                    // 3
        int i = 0;
        while (i < cs.length)
        {
            Enum<?> e = (Enum<?>) cs[i];
            Magic.printStr("  " + e.name() + "=" + e.ordinal() + "\n");     // MERCURY=0 VENUS=1 EARTH=2
            i += 1;
        }

        Magic.printStr("String isEnum=" + (String.class.isEnum() ? 1 : 0)
                + " constants null=" + (String.class.getEnumConstants() == null ? 1 : 0) + "\n"); // 0 1
    }
}
