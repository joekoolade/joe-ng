/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
package demo;

import java.util.HashMap;
import magic.Magic;

/**
 * Real {@code Integer.valueOf} autoboxing on metal, with the {@code [-128,127]} cache seeded by the loader
 * (its CDS/property-driven <clinit> isn't runnable). Small keys hit the cache (valueOf returns the SAME
 * interned instance — identity ==); large keys take the {@code new Integer} path (distinct instances,
 * content-matched). HashMap dispatches key.hashCode()/equals() through the mini {@link Object} root's vtable
 * slots down the {@code Integer -> Number -> Object} chain into Integer's real implementations.
 */
public class BoxingDemo
{
    public static void main(String[] args)
    {
        HashMap map = new HashMap();
        map.put(Integer.valueOf(5), "five");                                 // cached
        map.put(Integer.valueOf(-100), "neg-hundred");                       // cached
        map.put(Integer.valueOf(1000), "thousand");                         // new Integer (out of cache)

        showStr("get(box 5)", (String) map.get(Integer.valueOf(5)));         // five
        showStr("get(box -100)", (String) map.get(Integer.valueOf(-100)));   // neg-hundred
        showStr("get(box 1000)", (String) map.get(Integer.valueOf(1000)));   // thousand (distinct box, content match)
        showInt("hashCode(box -100)", Integer.valueOf(-100).hashCode());     // -100
        showInt("size", map.size());                                         // 3

        // The cache: valueOf returns the SAME instance for small ints, a fresh one otherwise.
        showBool("valueOf(5)==valueOf(5) cached", Integer.valueOf(5) == Integer.valueOf(5));         // 1
        showBool("valueOf(1000)==valueOf(1000) new", Integer.valueOf(1000) == Integer.valueOf(1000)); // 0

        // JLS 5.1.7 FOR THE OTHER WRAPPERS, and these are the arms that used to FAIL. Character [0,127], Byte
        // (every value) and Short [-128,127] must intern exactly as Integer does -- and those three overlays
        // carried NO cache, so each of the "interned" arms below read 0 on unmodified main: autoboxing handed
        // back a fresh box and `==` was false where the specification says it is true.
        showBool("char 'A' interned", Character.valueOf('A') == Character.valueOf('A'));                    // 1
        showBool("byte 5 interned", Byte.valueOf((byte) 5) == Byte.valueOf((byte) 5));                      // 1
        showBool("short 5 interned", Short.valueOf((short) 5) == Short.valueOf((short) 5));                 // 1

        // The out-of-range arms must stay 0. JLS 5.1.7 mandates nothing above 127, so a cache that had
        // quietly widened -- or one keyed on the wrong range -- shows HERE and not above. (Byte has no such
        // arm, and that is not an omission: every byte value is inside the mandated range.)
        showBool("char 200 fresh", Character.valueOf((char) 200) != Character.valueOf((char) 200));         // 1
        showBool("short 1000 fresh", Short.valueOf((short) 1000) != Short.valueOf((short) 1000));           // 1

        // BOTH ENDS of each range. The byte/short slot is value+128, so an off-by-one in that offset is an
        // AIOOBE at an END rather than a wrong number -- which means only an end arm can reach it, and the
        // interning arms above would pass right through the bug.
        showInt("char ends", Character.valueOf((char) 0).charValue()
                             + Character.valueOf((char) 127).charValue());                                 // 127
        showInt("byte ends", Byte.valueOf((byte) -128).byteValue()
                             + Byte.valueOf((byte) 127).byteValue());                                      // -1
        showInt("short ends", Short.valueOf((short) -128).shortValue()
                             + Short.valueOf((short) 127).shortValue());                                   // -1

        // Autoboxing must reach the SAME cache a direct valueOf does -- a second cache would show here.
        Character autoChar = 'A';
        Byte autoByte = (byte) 5;
        Short autoShort = (short) 5;
        showBool("autobox char == valueOf", autoChar == Character.valueOf('A'));                            // 1
        showBool("autobox byte == valueOf", autoByte == Byte.valueOf((byte) 5));                            // 1
        showBool("autobox short == valueOf", autoShort == Short.valueOf((short) 5));                        // 1
    }

    private static void showBool(String label, boolean v)
    {
        showInt(label, v ? 1 : 0);
    }

    private static void showStr(String label, String v)
    {
        Magic.printStr("  Boxing.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(v);
        Magic.printStr("\n");
    }

    private static void showInt(String label, int v)
    {
        showStr(label, Integer.toString(v));
    }
}
