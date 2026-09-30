/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-29
 */

/**
 * Exercises {@code java.lang.Boolean}, {@code java.lang.Byte} and {@code java.lang.Short} -- the interning
 * contract, the parsing and range checks, the signed/unsigned pairs, the narrowing accessors, and the
 * {@code logical*} statics.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>THE CONTROL IS THE THREE OVERLAYS RESTORED, and one source does NOT compile against both worlds: they
 * declared SIXTEEN fewer members than stock, so the arms naming {@code Boolean.hashCode(boolean)},
 * {@code logicalAnd}/{@code logicalOr}/{@code logicalXor}, {@code Byte.shortValue}, {@code Byte.toString(byte)},
 * {@code Byte.compareUnsigned}, {@code Short.byteValue}, {@code Short.toString(short)},
 * {@code Short.compareUnsigned}, {@code Short.reverseBytes}, {@code Short.toUnsignedInt} and
 * {@code Short.toUnsignedLong} do not exist for javac to bind. That is the overlay-drops-stock-members
 * finding stated as a compile error rather than inferred, and it is why the control is reported as arms WRONG
 * plus arms that would not COMPILE.
 *
 * <p>THE SIGNED/UNSIGNED PAIRS ARE THE SHARPEST ARMS, because the plausible wrong implementation passes
 * everything else. {@code Byte.compare((byte) -1, (byte) 1)} is NEGATIVE and
 * {@code Byte.compareUnsigned((byte) -1, (byte) 1)} is POSITIVE -- 255 against 1 -- so an implementation that
 * aliased the unsigned form to the signed one differs on exactly those arms and nowhere else. Same shape as
 * the {@code char}-is-unsigned / {@code byte}-is-signed arms the {@code DualPivotQuicksort} deletion used.
 * {@code Short.toUnsignedInt((short) -1)} is 65535 where a sign-extending implementation answers -1.
 *
 * <p>THE NARROWING ACCESSORS ARE REACHED THROUGH A {@code Number}-TYPED REFERENCE, deliberately. Stock
 * {@code Number} declares {@code byteValue}/{@code shortValue} and this VM's {@code Number} overlay drops
 * them, so such a call resolves against the RECEIVER through the late-dispatch tier -- and a receiver whose
 * own class also dropped the member resolves NOWHERE. {@code ((Number) Short.valueOf((short) 300)).byteValue()}
 * is also a TRUNCATION (300 becomes 44), which is a wrong ANSWER rather than a missing one if the narrowing
 * is done by the wrong width.
 *
 * <p>THE INTERNING ARMS PIN JLS 5.1.7 FROM BOTH SIDES. A cached box must be IDENTICAL and one above the cache
 * must be FRESH -- the second half is what a cache that had quietly widened would fail, and it is the only
 * arm that can see it. Both ENDS of each range are asserted because the slot is {@code value + 128}, so an
 * off-by-one in that offset is an exception at an end rather than a wrong number, and the interning arms pass
 * straight through it.
 *
 * <p>THE PARSING AND RANGE ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both states
 * is not a control: {@code parseByte}/{@code parseShort}/{@code parseBoolean}, {@code decode}, the
 * out-of-range throws, {@code equals}/{@code hashCode}/{@code compareTo}/{@code toString} and the {@code TYPE}
 * identities were all correct under the overlays -- they are what those overlays had been patched into being.
 *
 * <p>NO NESTED OR ANONYMOUS CLASSES, so the probe is exactly one class file and a default-package
 * {@code WrapProbe$1} cannot hide an arm.
 */
public class WrapProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    private static String bool(boolean b)
    {
        return b ? "1" : "0";
    }

    private static String sign(int v)
    {
        return v < 0 ? "lt" : (v > 0 ? "gt" : "eq");
    }

    private static String thrown(String what)
    {
        return "threw " + what;
    }

    public static void main(String[] args)
    {
        System.out.println("-- Boolean: interning and the singletons --");
        say("valueOf(true) == TRUE", bool(Boolean.valueOf(true) == Boolean.TRUE));
        say("valueOf(false) == FALSE", bool(Boolean.valueOf(false) == Boolean.FALSE));
        say("valueOf(true) == valueOf(true)", bool(Boolean.valueOf(true) == Boolean.valueOf(true)));
        Boolean autoTrue = true;
        say("autobox true == TRUE", bool(autoTrue == Boolean.TRUE));
        say("TRUE != FALSE", bool(Boolean.TRUE != Boolean.FALSE));
        say("TYPE == boolean.class", bool(Boolean.TYPE == boolean.class));

        System.out.println("-- Boolean: values --");
        say("TRUE.booleanValue()", bool(Boolean.TRUE.booleanValue()));
        say("TRUE.hashCode()", "" + Boolean.TRUE.hashCode());
        say("FALSE.hashCode()", "" + Boolean.FALSE.hashCode());
        say("TRUE.toString()", Boolean.TRUE.toString());
        say("Boolean.toString(false)", Boolean.toString(false));
        say("TRUE.equals(TRUE)", bool(Boolean.TRUE.equals(Boolean.TRUE)));
        say("TRUE.equals(FALSE)", bool(Boolean.TRUE.equals(Boolean.FALSE)));
        say("TRUE.equals(\"true\")", bool(Boolean.TRUE.equals("true")));
        say("TRUE.compareTo(FALSE)", sign(Boolean.TRUE.compareTo(Boolean.FALSE)));
        say("FALSE.compareTo(TRUE)", sign(Boolean.FALSE.compareTo(Boolean.TRUE)));
        say("compare(true,true)", sign(Boolean.compare(true, true)));
        say("parseBoolean(\"TrUe\")", bool(Boolean.parseBoolean("TrUe")));
        say("parseBoolean(\"yes\")", bool(Boolean.parseBoolean("yes")));
        say("valueOf(\"true\") == TRUE", bool(Boolean.valueOf("true") == Boolean.TRUE));

        System.out.println("-- Boolean: stock-only statics --");
        say("hashCode(true)", "" + Boolean.hashCode(true));
        say("hashCode(false)", "" + Boolean.hashCode(false));
        say("hashCode(true) == TRUE.hashCode()", bool(Boolean.hashCode(true) == Boolean.TRUE.hashCode()));
        say("logicalAnd(true,false)", bool(Boolean.logicalAnd(true, false)));
        say("logicalOr(true,false)", bool(Boolean.logicalOr(true, false)));
        say("logicalXor(true,true)", bool(Boolean.logicalXor(true, true)));
        say("logicalXor(true,false)", bool(Boolean.logicalXor(true, false)));

        System.out.println("-- Byte: interning, both ends, above the range --");
        say("valueOf(5) interned", bool(Byte.valueOf((byte) 5) == Byte.valueOf((byte) 5)));
        Byte autoByte = (byte) 5;
        say("autobox 5 == valueOf(5)", bool(autoByte == Byte.valueOf((byte) 5)));
        say("valueOf(MIN) interned", bool(Byte.valueOf(Byte.MIN_VALUE) == Byte.valueOf(Byte.MIN_VALUE)));
        say("valueOf(MAX) interned", bool(Byte.valueOf(Byte.MAX_VALUE) == Byte.valueOf(Byte.MAX_VALUE)));
        say("MIN end value", "" + Byte.valueOf(Byte.MIN_VALUE).byteValue());
        say("MAX end value", "" + Byte.valueOf(Byte.MAX_VALUE).byteValue());
        say("MIN_VALUE/MAX_VALUE", Byte.MIN_VALUE + "/" + Byte.MAX_VALUE);
        say("SIZE/BYTES", Byte.SIZE + "/" + Byte.BYTES);
        say("TYPE == byte.class", bool(Byte.TYPE == byte.class));

        System.out.println("-- Byte: values and parsing --");
        say("(byte)-1 hashCode", "" + Byte.valueOf((byte) -1).hashCode());
        say("(byte)-1 toString", Byte.valueOf((byte) -1).toString());
        say("equals same/other", bool(Byte.valueOf((byte) 7).equals(Byte.valueOf((byte) 7)))
                + "/" + bool(Byte.valueOf((byte) 7).equals(Byte.valueOf((byte) 8))));
        say("equals across types", bool(Byte.valueOf((byte) 7).equals(Short.valueOf((short) 7))));
        say("compareTo -1,1", sign(Byte.valueOf((byte) -1).compareTo(Byte.valueOf((byte) 1))));
        say("compare -1,1", sign(Byte.compare((byte) -1, (byte) 1)));
        say("parseByte(\"127\")", "" + Byte.parseByte("127"));
        say("parseByte(\"-128\")", "" + Byte.parseByte("-128"));
        say("parseByte(\"7f\",16)", "" + Byte.parseByte("7f", 16));
        say("decode(\"0x7F\")", "" + Byte.decode("0x7F").byteValue());
        say("decode(\"010\")", "" + Byte.decode("010").byteValue());
        say("decode(\"#7F\")", "" + Byte.decode("#7F").byteValue());
        say("toUnsignedInt(-1)", "" + Byte.toUnsignedInt((byte) -1));
        say("toUnsignedLong(-1)", "" + Byte.toUnsignedLong((byte) -1));
        try
        {
            Byte.parseByte("128");
            say("parseByte(\"128\")", "no throw");
        }
        catch (NumberFormatException e)
        {
            say("parseByte(\"128\")", thrown("NumberFormatException"));
        }
        try
        {
            Byte.parseByte("xyz");
            say("parseByte(\"xyz\")", "no throw");
        }
        catch (NumberFormatException e)
        {
            say("parseByte(\"xyz\")", thrown("NumberFormatException"));
        }

        System.out.println("-- Byte: stock-only --");
        say("Byte.toString((byte)-42)", Byte.toString((byte) -42));
        say("compareUnsigned -1,1", sign(Byte.compareUnsigned((byte) -1, (byte) 1)));
        say("compareUnsigned 1,-1", sign(Byte.compareUnsigned((byte) 1, (byte) -1)));
        say("compareUnsigned equal", sign(Byte.compareUnsigned((byte) -1, (byte) -1)));
        say("shortValue of (byte)-1", "" + Byte.valueOf((byte) -1).shortValue());

        System.out.println("-- Short: interning, both ends, above the range --");
        say("valueOf(5) interned", bool(Short.valueOf((short) 5) == Short.valueOf((short) 5)));
        Short autoShort = (short) 5;
        say("autobox 5 == valueOf(5)", bool(autoShort == Short.valueOf((short) 5)));
        say("valueOf(-128) interned", bool(Short.valueOf((short) -128) == Short.valueOf((short) -128)));
        say("valueOf(127) interned", bool(Short.valueOf((short) 127) == Short.valueOf((short) 127)));
        say("valueOf(1000) FRESH", bool(Short.valueOf((short) 1000) == Short.valueOf((short) 1000)));
        say("valueOf(-129) FRESH", bool(Short.valueOf((short) -129) == Short.valueOf((short) -129)));
        say("-128 end value", "" + Short.valueOf((short) -128).shortValue());
        say("127 end value", "" + Short.valueOf((short) 127).shortValue());
        say("MIN_VALUE/MAX_VALUE", Short.MIN_VALUE + "/" + Short.MAX_VALUE);
        say("SIZE/BYTES", Short.SIZE + "/" + Short.BYTES);
        say("TYPE == short.class", bool(Short.TYPE == short.class));

        System.out.println("-- Short: values and parsing --");
        say("(short)-1 hashCode", "" + Short.valueOf((short) -1).hashCode());
        say("(short)-1 toString", Short.valueOf((short) -1).toString());
        say("compare -1,1", sign(Short.compare((short) -1, (short) 1)));
        say("parseShort(\"32767\")", "" + Short.parseShort("32767"));
        say("parseShort(\"-32768\")", "" + Short.parseShort("-32768"));
        say("parseShort(\"7fff\",16)", "" + Short.parseShort("7fff", 16));
        say("decode(\"0x7FFF\")", "" + Short.decode("0x7FFF").shortValue());
        try
        {
            Short.parseShort("32768");
            say("parseShort(\"32768\")", "no throw");
        }
        catch (NumberFormatException e)
        {
            say("parseShort(\"32768\")", thrown("NumberFormatException"));
        }

        System.out.println("-- Short: stock-only --");
        say("Short.toString((short)-4242)", Short.toString((short) -4242));
        say("compareUnsigned -1,1", sign(Short.compareUnsigned((short) -1, (short) 1)));
        say("compareUnsigned 1,-1", sign(Short.compareUnsigned((short) 1, (short) -1)));
        say("toUnsignedInt(-1)", "" + Short.toUnsignedInt((short) -1));
        say("toUnsignedLong(-1)", "" + Short.toUnsignedLong((short) -1));
        say("toUnsignedInt(256)", "" + Short.toUnsignedInt((short) 256));
        say("reverseBytes(0x1234)", "" + Short.reverseBytes((short) 0x1234));
        say("reverseBytes(0x00FF)", "" + Short.reverseBytes((short) 0x00FF));
        say("reverseBytes twice", "" + Short.reverseBytes(Short.reverseBytes((short) 0x1234)));
        say("byteValue of (short)300", "" + Short.valueOf((short) 300).byteValue());

        System.out.println("-- the narrowing accessors through a Number-typed reference --");
        Number nb = Byte.valueOf((byte) -1);
        Number ns = Short.valueOf((short) 300);
        say("Number(Byte -1).intValue", "" + nb.intValue());
        say("Number(Byte -1).longValue", "" + nb.longValue());
        say("Number(Byte -1).shortValue", "" + nb.shortValue());
        say("Number(Short 300).byteValue", "" + ns.byteValue());
        say("Number(Short 300).intValue", "" + ns.intValue());
        say("Number(Short 300).doubleValue", "" + (long) (ns.doubleValue() * 10));

        System.out.println("WrapProbe done");
    }
}
