/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-20
 */
import java.lang.invoke.MethodType;

/**
 * STOCK {@code java.lang.invoke.MethodType} on metal -- increment 1 of the MethodHandle arc, and the arm
 * that decides whether the rest of the arc can lean on stock code at all.
 *
 * <p><b>Why this is not an overlay.</b> The first cut of this increment hand-wrote a MethodType overlay on
 * the claim that a faithful copy "cannot work", generalised from counting imports (10 uses of
 * {@code MethodTypeForm}, 4 of {@code Invokers}). Reading the construction path refutes it:
 * {@code MethodType.makeImpl} does {@code mt.form = MethodTypeForm.findForm(mt)}, and that constructor is
 * pure shape computation -- count primitives and long/double slots, derive the basic type, allocate two
 * EMPTY {@code SoftReference} caches. Neither {@code LambdaForm} nor {@code Invokers} is reached; the caches
 * fill lazily and {@code Invokers} only through {@code mt.invokers()}, which nothing here calls.
 *
 * <p><b>The arms are chosen so that a MethodType which merely LOOKS built still fails.</b>
 * <ul>
 *   <li><b>Interning identity</b> is the load-bearing one. {@code StringConcatFactory} keys its
 *       generated-class cache on MethodType and relies on {@code erasedArgs} returning the SAME instance
 *       when nothing changed, so a MethodType that is merely {@code equals} would break it far away and
 *       silently. Two independently built types must be {@code ==}.
 *   <li><b>{@code erase()} and {@code unwrap()} force {@code MethodTypeForm} to do real work</b> --
 *       {@code canonicalize(mt, ERASE)} and {@code Wrapper.forPrimitiveType} -- which is precisely the layer
 *       the abandoned overlay claimed was unreachable. If the reading above is wrong, these are where it
 *       shows.
 *   <li><b>The descriptor round-trip exercises {@code Class.forName}</b> out of a descriptor string, and a
 *       three-deep array type, which is where a naive parser stops one character early.
 *   <li><b>Negative arms</b> ({@code erase} of an all-primitive type returning {@code this}; two types that
 *       differ only in return type NOT interning together) exist so "fixed" cannot quietly mean "answers
 *       the same thing for everything".
 * </ul>
 *
 * <p>Every line is byte-comparable against the same source on a stock JVM -- run it on the host and diff.
 * {@code parameterSlotCount()} is deliberately absent: it is package-private in stock (which
 * {@code StringConcatFactory} depends on), so a probe outside {@code java.lang.invoke} cannot call it, and
 * pretending otherwise would mean changing stock's modifier.
 */
public class MethodTypeProbe
{
    public static void main(String[] args)
    {
        MethodType a = MethodType.methodType(int.class, String.class);
        MethodType b = MethodType.methodType(int.class, String.class);

        // THE PROMISE StringConcatFactory's cache is built on: interning, not just equality.
        System.out.println("intern same    = " + (a == b) + " (want true)");
        System.out.println("equals         = " + a.equals(b) + " (want true)");
        System.out.println("hash equal     = " + (a.hashCode() == b.hashCode()) + " (want true)");

        // Shape accessors.
        System.out.println("paramCount     = " + a.parameterCount() + " (want 1)");
        System.out.println("paramType(0)   = " + a.parameterType(0).getName() + " (want java.lang.String)");
        System.out.println("returnType     = " + a.returnType().getName() + " (want int)");
        System.out.println("toString       = " + a + " (want (String)int)");

        // MethodTypeForm's real work: ERASE canonicalisation. Reference -> Object, primitives untouched.
        MethodType mixed = MethodType.methodType(String.class, String.class, int.class, long.class);
        System.out.println("erase          = " + mixed.erase() + " (want (Object,int,long)Object)");

        // NEGATIVE: an already-erased, all-primitive type must come back as the SAME object, not a copy.
        MethodType prim = MethodType.methodType(int.class, int.class, double.class);
        System.out.println("erase noop     = " + (prim.erase() == prim) + " (want true)");

        // Wrapper.forPrimitiveType, the other half of the layer the overlay claimed was unreachable.
        System.out.println("wrap           = " + prim.wrap() + " (want (Integer,Double)Integer)");
        System.out.println("unwrap back    = " + (prim.wrap().unwrap() == prim) + " (want true)");
        System.out.println("hasPrimitives  = " + prim.hasPrimitives() + " (want true)");
        System.out.println("generic        = " + prim.generic() + " (want (Object,Object)Object)");

        // Derivation. Each must intern with an independently built equal type.
        MethodType changed = a.changeReturnType(boolean.class);
        System.out.println("changeReturn   = " + (changed == MethodType.methodType(boolean.class, String.class)) + " (want true)");
        System.out.println("insertParams   = " + a.insertParameterTypes(0, long.class) + " (want (long,String)int)");
        System.out.println("dropParams     = " + mixed.dropParameterTypes(1, 3) + " (want (String)String)");
        System.out.println("appendParams   = " + a.appendParameterTypes(char.class) + " (want (String,char)int)");

        // NEGATIVE: differing only in return type must NOT be the same instance.
        System.out.println("distinct ret   = " + (a == MethodType.methodType(long.class, String.class)) + " (want false)");

        // Descriptor round-trip, including a three-deep array -- Class.forName out of a descriptor.
        String desc = "(IJ[[[Ljava/lang/String;)Ljava/lang/Object;";
        MethodType parsed = MethodType.fromMethodDescriptorString(desc, null);
        System.out.println("parsed         = " + parsed + " (want (int,long,String[][][])Object)");
        System.out.println("desc roundtrip = " + parsed.toMethodDescriptorString().equals(desc) + " (want true)");
        System.out.println("parsed interns = " + (parsed == MethodType.fromMethodDescriptorString(desc, null)) + " (want true)");

        // The void-return and no-arg corners, where an off-by-one descriptor walk shows up.
        MethodType v = MethodType.methodType(void.class);
        System.out.println("void desc      = " + v.toMethodDescriptorString() + " (want ()V)");
        System.out.println("generic(2)     = " + MethodType.genericMethodType(2) + " (want (Object,Object)Object)");

        System.out.println("MethodTypeProbe done");
    }
}
