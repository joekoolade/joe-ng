/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-26
 */
package demo;

import magic.Magic;

/**
 * The invokedynamic proof: real string concatenation. `"value=" + n + " k=" + k + "\n"` compiles to an
 * {@code invokedynamic StringConcatFactory.makeConcatWithConstants}, which the on-metal JIT intrinsifies
 * into a byte[] build wrapped in a mini {@code java/lang/String}. The writer embeds only these raw bytes;
 * the Loader demand-loads {@code java/lang/String}, JITs the concat, and {@code Magic.printStr} prints it.
 *
 * <p>Only concat results (real String objects) are printed — bare string literals are still raw byte[]
 * on metal, so we keep the newline inside the concat rather than passing a literal to printStr.
 */
public class ConcatDemo
{
    public static void main(String[] args)
    {
        int n = 42;
        int k = 7;
        String s = "value=" + n + " k=" + k + "\n";     // int args + literals
        Magic.printStr(s);

        int sum = n + k;
        String t = "sum=" + sum + " (neg " + (0 - sum) + ")\n";
        Magic.printStr(t);

        // slice 1b: a String-object arg (s2 feeds a second concat) + a long arg + a bare-literal print.
        long big = 1234567890123L;
        String label = "val=" + n;                      // -> a String object
        String u = label + " big=" + big + "!\n";       // String arg (label) + long arg (big)
        Magic.printStr(u);
        Magic.printStr("bare literal ok\n");            // a raw byte[] literal -> printStr handles it too

        // A NULL REFERENCE CONCATENATES AS "null" (JLS 15.18.1). This printed an EMPTY STRING, and not
        // merely cosmetically: the append read `0 + 16`, i.e. address 16 -- low firmware memory, readable --
        // so a non-zero length there would have appended that many bytes of garbage.
        String ns = null;
        Object no = null;
        System.out.println("null String  = [" + ns + "] (want [null])");
        System.out.println("null Object  = [" + no + "] (want [null])");
        // Surrounded by text on BOTH sides: an append that emitted nothing still looks right at the end.
        System.out.println("null middle  = [a" + ns + "b] (want [anullb])");
        // Two in a row, so a fix that emits one "null" and stops is caught.
        System.out.println("null twice   = [" + ns + no + "] (want [nullnull])");
        // A non-null reference beside it, so the fix cannot be "always print null".
        System.out.println("null mixed   = [" + ns + "x" + no + "] (want [nullxnull])");

        // A BOOLEAN CONCATENATES AS THE WORD (JLS 15.18.1), not as 1/0. `Baseline.appendArg` routed a 'Z'
        // argument to SC_INT for the life of the project, so this whole block printed 1 and 0.
        //
        // EVERY VALUE HERE IS DERIVED FROM A RUNTIME COMPARISON, and that is load-bearing rather than
        // stylistic: javac constant-folds `"[" + true + "]"` into the literal "[true]" at compile time, so
        // an arm written with a literal is a BAKED STRING that never reaches the concat lowering and passes
        // in both states. `n` and `k` are ordinary locals, so `n > k` is computed at run time and a real
        // invokedynamic is emitted.
        boolean bt = n > k;                             // true, but not a compile-time constant
        boolean bf = n < k;                             // false, ditto
        System.out.println("bool true    = [" + bt + "] (want [true])");
        System.out.println("bool false   = [" + bf + "] (want [false])");
        // Surrounded on BOTH sides, so an append emitting nothing still looks right at end-of-line.
        System.out.println("bool middle  = [a" + bt + "b] (want [atrueb])");
        // Two in a row: catches a fix that emits one and stops, and pins the ORDER.
        System.out.println("bool twice   = [" + bt + bf + "] (want [truefalse])");
        // THE ARM THAT DISCRIMINATES: RUNTIME ints beside booleans, so a "fix" that made SC_INT print words
        // would pass every arm above and fail this one -- 42 and 7 must still render as DIGITS.
        //
        // `n` and `k` rather than literals, and CHECKED rather than assumed: written as `+ bt + 1 + bf + 0`
        // the descriptor is (ZZ), because javac folds a constant int straight into the recipe TEXT -- the
        // digits would never reach SC_INT and the arm would discriminate nothing. With locals it is (ZIZI).
        System.out.println("bool vs int  = [" + bt + n + bf + k + "] (want [true42false7])");

        // FLOAT AND DOUBLE CONCAT (JLS 15.18.1). This block was a HARD FAILURE -- `JIT unsupported
        // reason=0 a=0xBA b=2` -- which is why OpcodeDemo still prints doubles as scaled longs.
        //
        // Runtime-derived again, for the reason the boolean arms record: javac folds a constant expression
        // straight into a string literal, and a folded arm never reaches the concat lowering at all.
        double d15 = 1.5;
        double dp1 = 0.1;
        double dnz = -0.0;
        double dbig = 1e20;
        double dsml = 1e-9;
        double zero = d15 - d15;                        // 0.0, but COMPUTED -- 0.0/0.0 would fold
        double dnan = zero / zero;
        double dinf = d15 / zero;
        float fp1 = 0.1f;
        float f15 = 1.5f;
        System.out.println("dbl 1.5      = [" + d15 + "] (want [1.5])");
        // 0.1 IS THE ARM WITH TEETH for the double half: its exact value is
        // 0.1000000000000000055511151231257827..., so anything but SHORTEST-round-trip prints that instead.
        System.out.println("dbl 0.1      = [" + dp1 + "] (want [0.1])");
        System.out.println("dbl -0.0     = [" + dnz + "] (want [-0.0])");
        System.out.println("dbl 1e20     = [" + dbig + "] (want [1.0E20])");
        System.out.println("dbl 1e-9     = [" + dsml + "] (want [1.0E-9])");
        System.out.println("dbl NaN      = [" + dnan + "] (want [NaN])");
        System.out.println("dbl Infinity = [" + dinf + "] (want [Infinity])");
        System.out.println("flt 0.1f     = [" + fp1 + "] (want [0.1])");
        System.out.println("flt 1.5f     = [" + f15 + "] (want [1.5])");
        // THE ARM THAT SEPARATES FLOAT FROM DOUBLE, and the reason float cannot just widen: shortest
        // round-trip is relative to the type's OWN precision, so the same value prints two strings.
        System.out.println("flt vs dbl   = [" + fp1 + "|" + (double) fp1 + "] (want [0.1|0.10000000149011612])");
        // ... and SC_INT must still render digits beside them. Descriptor is (DID): the trailing 7 is a
        // CONSTANT and javac folds it into the recipe TEXT, so it never reaches SC_LONG -- said here rather
        // than left implied, because that is exactly the mistake the boolean arm made and had to correct.
        System.out.println("dbl vs int   = [" + d15 + n + dp1 + 7L + "] (want [1.5420.17])");

        // A BOXED WRAPPER CONCATENATES THROUGH ITS OWN toString (JLS 15.18.1: every reference argument is
        // String.valueOf(obj)). `Baseline.appendArg` collapses every reference to ONE helper, which read
        // whatever it was handed AS a String -- so a non-String's first FIELD became its byte[] pointer.
        // For an Integer that field is the int, so `"x" + Integer.valueOf(5)` loaded ADDRESS 21 and faulted
        // inside the VM's own concat helper, naming nothing the program had done. Found by BoxCacheProbe
        // walking into it while measuring something else, on unmodified main.
        //
        // THE EIGHT WRAPPERS ARE THE WHOLE OF THE REACHABLE GAP, and that is MEASURED with javap rather
        // than assumed: javac hands the indy a reference only for String and the eight boxes, and emits
        // `invokestatic String.valueOf(Object)` ITSELF for every other reference type -- Number, Object,
        // CharSequence, an array, a user class. So arms built from a user class with its own toString pass
        // on an unfixed VM and discriminate NOTHING; a first cut of this block had five of them, and
        // `javap -c` is what said so. Same trap as the constant folding two blocks up, one layer out.
        //
        // EVERY VALUE IS RUNTIME-DERIVED for that same reason: `Integer.valueOf(42)` written as a literal
        // would still box, but `n` keeps the arm honest against any later folding.
        System.out.println("box Integer  = [" + Integer.valueOf(n) + "] (want [42])");
        System.out.println("box Long     = [" + Long.valueOf(big) + "] (want [1234567890123])");
        System.out.println("box Boolean  = [" + Boolean.valueOf(bt) + "] (want [true])");
        System.out.println("box Character= [" + Character.valueOf((char) (n + 23)) + "] (want [A])");
        System.out.println("box Byte     = [" + Byte.valueOf((byte) k) + "] (want [7])");
        System.out.println("box Short    = [" + Short.valueOf((short) n) + "] (want [42])");
        // The two that reach a FORMATTER through the box's own toString rather than through SC_DOUBLE.
        System.out.println("box Float    = [" + Float.valueOf(fp1) + "] (want [0.1])");
        System.out.println("box Double   = [" + Double.valueOf(dp1) + "] (want [0.1])");
        // A NEGATIVE box, so an arm cannot pass by reading a plausible small positive out of the object.
        System.out.println("box negative = [" + Integer.valueOf(0 - n) + "] (want [-42])");
        // ABOVE THE CACHE: a heap box rather than an image one, so the fix cannot depend on where it lives.
        System.out.println("box uncached = [" + Integer.valueOf(n * 100) + "] (want [4200])");

        // AND THE OTHER KINDS MUST STILL WORK BESIDE ONE: a String, an int and a box in one recipe, so a
        // fix that sent EVERY reference through toString would be caught here rather than passing quietly.
        System.out.println("box vs rest  = [" + label + n + Integer.valueOf(k) + "] (want [val=42427])");
    }
}
