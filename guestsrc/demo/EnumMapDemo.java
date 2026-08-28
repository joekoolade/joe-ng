package demo;

import java.util.EnumMap;

/**
 * Reduction of the failure the zip JUnit run hit with the harness seed removed: an
 * {@code ArrayIndexOutOfBoundsException} at {@code EnumMap.getKeyUniverse}, whose whole body is
 * {@code SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType)} — a single invokeinterface.
 * AIOOBE (rather than the directory-miss NPE) means the itable for JavaLangAccess WAS found on the receiver
 * and the SLOT's entry is empty or wrong.
 */
public class EnumMapDemo
{
    enum Color { RED, GREEN, BLUE }

    public static void main(String[] args)
    {
        EnumMap<Color, String> m = new EnumMap<>(Color.class);
        m.put(Color.RED, "r");
        m.put(Color.BLUE, "b");
        System.out.println("enummap size=" + m.size() + " red=" + m.get(Color.RED) + " blue=" + m.get(Color.BLUE));
    }
}
