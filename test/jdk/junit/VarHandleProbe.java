/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-30
 */

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Does joe-ng build and drive a REAL, STOCK {@code VarHandle}? The 122-line hand-written overlay this
 * replaces had six REFERENCE-typed ops, and the loader resolved a signature-polymorphic site BY NAME ALONE,
 * so one {@code set} served every {@code set} whatever its descriptor -- a handle over an {@code int} field
 * stored the int AS A REFERENCE.
 *
 * <p>EVERY ARM IS WIDTH-SPECIFIC ON PURPOSE. A probe that only exercised reference fields would pass against
 * the retired overlay and prove nothing; the whole point is that `int`, `long`, `boolean`, `byte`, `char`,
 * `short`, `float` and `double` each reach a DIFFERENT template-generated handle and a different
 * {@code Unsafe} accessor width. The reference arm is the CONTROL: it is the one shape the old overlay got
 * right, so it must still pass.
 *
 * <p>THE NEGATIVE VALUES ARE NOT DECORATION. A narrow field occupies a full 8-byte slot on this VM, so a
 * sign-extension slip is invisible for a positive value and wrong for a negative one -- the exact
 * discriminator the Unsafe accessor arc needed. `-2` appears at byte/short/int/long width for that reason,
 * and `char` carries 65535 because it is the one narrow type that is UNSIGNED.
 *
 * <p>Run on a host JVM the same source reaches the real JDK, so the output is a byte-for-byte oracle.
 */
public class VarHandleProbe
{
    // The target's fields are deliberately NOT final and NOT volatile: a VarHandle over a final field is a
    // different (read-only) handle shape, and volatility changes nothing about the access width.
    static class T
    {
        int i;
        long j;
        boolean z;
        byte b;
        char c;
        short s;
        float f;
        double d;
        String ref;
    }

    /** An INSTANCE field of THIS class, for the MhUtil arm below: that form binds against the caller. */
    int mine;

    /**
     * THE CONDITION, not just the shape: a class that binds a handle to its OWN field from its OWN
     * {@code <clinit>}, through MhUtil's no-receiver form. That is what all 30 java.base sites do
     * ({@code Socket}, {@code Phaser}, {@code Exchanger}, {@code FutureTask}, {@code Striped64},
     * {@code CompletableFuture}, {@code SubmissionPublisher}) and it is NOT what the `main`-called arm below
     * exercises: a pc inside {@code main} lies in a code block the method registry owns, so the caller
     * lookup answered correctly there by containing the pc rather than by being right. A pc inside a
     * {@code <clinit>} does not -- initializer bodies are compiled into their own buffers and registered in
     * no table -- which is how {@code java/net/Socket} got a handle whose receiverType was
     * {@code SharedSecrets}: an arbitrary neighbouring class, and then a field offset for a field that
     * class does not have.
     */
    static class Clinit
    {
        static final VarHandle VH =
                jdk.internal.invoke.MhUtil.findVarHandle(MethodHandles.lookup(), "own", int.class);

        int own;
    }

    static int failures;

    static void check(String what, String got, String want)
    {
        if (got.equals(want))
        {
            System.out.println("ok   " + what + " = " + got + " (want " + want + ")");
        }
        else
        {
            failures++;
            System.out.println("FAIL " + what + " = " + got + " (want " + want + ")");
        }
    }

    static VarHandle vh(String name, Class<?> type) throws Exception
    {
        return MethodHandles.lookup().findVarHandle(T.class, name, type);
    }

    public static void main(String[] args) throws Exception
    {
        T t = new T();

        // ---- STEP A: is a real generated handle CONSTRUCTED, and is it the right WIDTH? -----------------
        // The class name is the evidence. The retired overlay answered java.lang.invoke.VarHandle for every
        // width; stock answers a different generated class per width, which is what makes the access typed.
        check("class int   ", vh("i", int.class).getClass().getName(),
                "java.lang.invoke.VarHandleInts$FieldInstanceReadWrite");
        check("class long  ", vh("j", long.class).getClass().getName(),
                "java.lang.invoke.VarHandleLongs$FieldInstanceReadWrite");
        check("class ref   ", vh("ref", String.class).getClass().getName(),
                "java.lang.invoke.VarHandleReferences$FieldInstanceReadWrite");

        // ---- STEP B: the access modes, at every width ---------------------------------------------------
        VarHandle vi = vh("i", int.class);
        vi.set(t, -2);
        check("int set/get ", Integer.toString((int) vi.get(t)) + "/" + Integer.toString(t.i), "-2/-2");
        check("int CAS     ", Boolean.toString(vi.compareAndSet(t, -2, 7)) + "/" + Integer.toString(t.i),
                "true/7");
        check("int CAS miss", Boolean.toString(vi.compareAndSet(t, -2, 9)) + "/" + Integer.toString(t.i),
                "false/7");
        check("int getAndAdd", Integer.toString((int) vi.getAndAdd(t, 3)) + "/" + Integer.toString(t.i),
                "7/10");
        check("int getAndSet", Integer.toString((int) vi.getAndSet(t, -5)) + "/" + Integer.toString(t.i),
                "10/-5");
        check("int volatile", Integer.toString((int) vi.getVolatile(t)), "-5");
        check("int bitwiseOr", Integer.toString((int) vi.getAndBitwiseOr(t, 1)) + "/" + Integer.toString(t.i),
                "-5/-5");

        VarHandle vj = vh("j", long.class);
        vj.set(t, -2L);
        check("long set/get", Long.toString((long) vj.get(t)) + "/" + Long.toString(t.j), "-2/-2");
        check("long CAS    ", Boolean.toString(vj.compareAndSet(t, -2L, 1L << 40)) + "/" + Long.toString(t.j),
                "true/1099511627776");

        VarHandle vz = vh("z", boolean.class);
        vz.set(t, true);
        check("bool set/get", Boolean.toString((boolean) vz.get(t)) + "/" + Boolean.toString(t.z),
                "true/true");

        VarHandle vb = vh("b", byte.class);
        vb.set(t, (byte) -2);
        check("byte set/get", Byte.toString((byte) vb.get(t)) + "/" + Byte.toString(t.b), "-2/-2");

        VarHandle vc = vh("c", char.class);
        vc.set(t, (char) 65535);
        check("char set/get", Integer.toString((int) (char) vc.get(t)) + "/" + Integer.toString((int) t.c),
                "65535/65535");

        VarHandle vs = vh("s", short.class);
        vs.set(t, (short) -2);
        check("short set/get", Short.toString((short) vs.get(t)) + "/" + Short.toString(t.s), "-2/-2");

        VarHandle vf = vh("f", float.class);
        vf.set(t, 1.5f);
        check("float set/get", Float.toString((float) vf.get(t)) + "/" + Float.toString(t.f), "1.5/1.5");

        VarHandle vd = vh("d", double.class);
        vd.set(t, 2.5);
        check("dbl set/get ", Double.toString((double) vd.get(t)) + "/" + Double.toString(t.d), "2.5/2.5");

        // The CONTROL: the one shape the retired overlay got right, so it must still pass.
        VarHandle vr = vh("ref", String.class);
        vr.set(t, "x");
        check("ref set/get ", (String) vr.get(t) + "/" + t.ref, "x/x");
        check("ref CAS     ", Boolean.toString(vr.compareAndSet(t, "x", "y")) + "/" + t.ref, "true/y");
        check("ref setRel  ", Boolean.toString(vr.compareAndSet(t, "nope", "z")) + "/" + t.ref, "false/y");

        // ---- MhUtil's NO-RECEIVER form: the declaring class comes from the CALLER's frame --------------
        // This is the form 30 java.base sites use -- Socket, Phaser, Exchanger, FutureTask, Striped64,
        // CompletableFuture, SubmissionPublisher -- against 4 for the receiver-taking one. Stock resolves it
        // through lookup.lookupClass(); joe-ng's Lookup carries none, so it reads the caller's frame
        // instead. The Lookup-direct arms above CANNOT reach this path, which is why it has its own arm:
        // an untested resolution of the DECLARING class is a wrong field offset, read and written for ever.
        VarHandle vmine = jdk.internal.invoke.MhUtil.findVarHandle(MethodHandles.lookup(), "mine", int.class);
        VarHandleProbe self = new VarHandleProbe();
        vmine.set(self, -7);
        check("mhutil caller", Integer.toString((int) vmine.get(self)) + "/" + Integer.toString(self.mine),
                "-7/-7");

        // ---- The SAME form, called from a <clinit> (where every real site calls it) ---------------------
        String clinitGot;
        try
        {
            Clinit cl = new Clinit();
            Clinit.VH.set(cl, -9);
            clinitGot = Integer.toString((int) Clinit.VH.get(cl)) + "/" + Integer.toString(cl.own);
        }
        catch (Throwable e)
        {
            clinitGot = e.getClass().getName() + ": " + e.getMessage();
        }
        check("mhutil clinit", clinitGot, "-9/-9");
        // WHY THE ROUND TRIP ABOVE IS THE DISCRIMINATOR, rather than needing a separate receiver arm: every
        // generated instance-field accessor opens `handle.receiverType.cast(holder)`, so a handle bound to
        // the wrong declaring class throws ClassCastException naming BOTH sides before it can touch memory.
        // That is precisely the hardware failure this arm reproduces ("Cannot cast java.net.Socket to
        // jdk.internal.access.SharedSecrets"). varType()/coordinateTypes() would have been the obvious way
        // to name the binding and are NOT usable: both read `vform`, which is null here by design.

        System.out.println("VarHandleProbe done, failures=" + failures);
    }
}
