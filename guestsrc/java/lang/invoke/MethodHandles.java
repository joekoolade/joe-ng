/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package java.lang.invoke;

import java.nio.ByteOrder;
import java.util.Objects;

import jdk.internal.misc.Unsafe;

/**
 * Minimal name-winning {@code java.lang.invoke.MethodHandles} overlay for joe-ng. The full class fronts the
 * MethodHandle runtime (denied on metal), but the one thing reached code wants from it -- a VarHandle bound
 * to an instance field -- is buildable without that runtime, so this overlay builds a REAL template-generated
 * handle (see {@code forInstanceField}).
 *
 * <p>{@link Lookup} is no longer a bare type: it CARRIES the binding, because both
 * {@code MhUtil.findVarHandle} forms now delegate to it rather than ignoring it.
 */
public final class MethodHandles
{
    private MethodHandles()
    {
    }

    /**
     * A SINGLETON rather than null now. It was null because the only caller ({@code MhUtil.findVarHandle})
     * ignores it -- but {@code jdk.internal.access.SharedSecrets.ensureClassInitialized} does
     * {@code lookup().ensureInitialized(c)}, and a null receiver there is an NPE inside java.base with no
     * useful frame. The instance costs one object for the life of the VM.
     */
    public static Lookup lookup()
    {
        return Lookup.INSTANCE;
    }

    /** Carries only what reached code calls; the overlay never dereferences a Lookup for MethodHandle work. */
    public static final class Lookup
    {
        static final Lookup INSTANCE = new Lookup();

        private Lookup()
        {
        }

        /**
         * A no-op returning {@code c}, and that is CORRECT here rather than a stub: joe-ng runs a class's
         * {@code <clinit>} on first ACTIVE USE, and every caller of this follows it immediately with a
         * {@code getstatic} on that same class -- which is an active use and triggers the initializer through
         * {@code noteInitNeeded}. Forcing it a moment earlier would change nothing observable.
         *
         * <p>Declared because stock {@code SharedSecrets} calls it before reading EVERY access shim, so
         * without it the first `getJavaXxxAccess()` traps -- and it catches only IllegalAccessException, which
         * a denylist trap is not.
         */
        public Class<?> ensureInitialized(Class<?> c)
        {
            return c;
        }

        /**
         * Bind a VarHandle to an instance field BY NAME, the same thing {@code MhUtil.findVarHandle} does.
         *
         * <p>Declared on Lookup itself because java.util.concurrent calls it DIRECTLY --
         * {@code ConcurrentLinkedQueue.<clinit>} does {@code l.findVarHandle(CLQ.class, "head", Node.class)}
         * -- whereas {@code java/net/Socket} reaches the same binding through MhUtil. Without it the
         * initializer traps, every handle stays null, and the first {@code offer()} NPEs in
         * {@code Node.<init>}, which is where the console launcher stopped.
         *
         * <p>ALL THREE ARGUMENTS ARE USED NOW, where the retired by-name shim ignored two of them: the
         * receiver gives the field's offset (Class-keyed, since a handle is built in a {@code <clinit>} with
         * no instance to read a TIB from) and the TYPE selects which template-generated handle to build, so
         * the access is width-typed by construction. See the {@code forInstanceField} helper below.
         *
         * <p>The two checked exceptions are stock's, and DECLARED rather than dropped: an overlay silently
         * loses every stock member it does not declare, and here javac is what said so -- a caller written
         * against stock does not compile without them. They do not change the DESCRIPTOR (throws is an
         * attribute), so metal resolution is unaffected either way; source compatibility is the point.
         */
        public VarHandle findVarHandle(Class<?> recv, String name, Class<?> type)
                throws NoSuchFieldException, IllegalAccessException
        {
            return forInstanceField(recv, name, type);
        }
    }

    /**
     * Builds a REAL, STOCK {@code VarHandle} for an instance field. This replaces the 122-line hand-written
     * {@code java.lang.invoke.VarHandle} overlay that used to shadow the name.
     *
     * <p>WHY THIS EXISTS AT ALL, rather than stock's own {@code VarHandles.makeFieldHandle}: that factory builds
     * a {@code MemberName} through the Lookup and needs the MethodHandle runtime, which this VM does not carry.
     * What it would HAND BACK, though, is an ordinary template-generated handle -- and those joe-ng can make
     * directly, because the generated classes are package-private members of THIS package.
     *
     * <p>WHY THE GENERATED HANDLES ARE THE RIGHT TARGET, which is the whole point of retiring the overlay. Each
     * is built on {@code jdk.internal.misc.Unsafe}: 91 {@code UNSAFE.*} calls across the template, over exactly
     * the {@code get*}/{@code put*} accessors and the {@code compareAndSet}/{@code compareAndExchange}/
     * {@code weakCompareAndSet}/{@code getAndSet}/{@code getAndAdd}/{@code getAndBitwise*} atomics whose joe-ng
     * deep-scan gap count is now ZERO. So stock's own code is the implementation, WIDTH-TYPED BY CONSTRUCTION,
     * where the overlay had six REFERENCE-typed ops serving every width.
     *
     * <p>THE DEFECT THAT RETIRES. The overlay's accessors were reference-typed and the loader resolved a
     * signature-polymorphic site BY NAME ALONE, so one {@code set} served every {@code set} whatever its
     * descriptor -- a VarHandle over an {@code int} field stored the int AS A REFERENCE. MEASURED over
     * java.base's 7417 classes: of 375 VarHandle call sites, 189 name one of the six ops the overlay declared,
     * and 96 of those are PRIMITIVE-valued -- {@code Phaser} (JJ), {@code Exchanger} (II), {@code FutureTask}
     * (I), {@code Striped64}, {@code SubmissionPublisher}. The overlay's note said "nothing reached so far does
     * that", which was a claim about ONE closure; the nearest offenders have been out of reach only because
     * {@code AtomicBoolean}, {@code AtomicReference}, the {@code Atomic*Array}s and
     * {@code ConcurrentSkipListMap} are THEMSELVES overlaid, so their stock sites never run.
     *
     * <p>STATED LIMIT: the handle's {@code vform} is whatever the generated class's {@code FORM} holds, and that
     * static is built by a {@code <clinit>} reaching {@code VarForm -> MethodType}. It is NOT on the access
     * path: {@code vform} is read at exactly three sites in stock {@code VarHandle} --
     * {@code isAccessModeSupported}, {@code getMethodHandle} and {@code updateVarForm} -- while every accessor
     * reads only {@code fieldOffset}, {@code receiverType} and (for the Object form) {@code fieldType}. So a
     * handle whose FORM failed to build still gets and sets correctly, and the three mode-reflection methods
     * fail LOUDLY rather than answering wrongly.
     */
    private static VarHandle forInstanceField(Class<?> recv, String name, Class<?> type)
    {
        long off = Unsafe.getUnsafe().objectFieldOffset(recv, name);
        if (type == int.class)     { return new VarHandleInts.FieldInstanceReadWrite(recv, off); }
        if (type == long.class)    { return new VarHandleLongs.FieldInstanceReadWrite(recv, off); }
        if (type == boolean.class) { return new VarHandleBooleans.FieldInstanceReadWrite(recv, off); }
        if (type == byte.class)    { return new VarHandleBytes.FieldInstanceReadWrite(recv, off); }
        if (type == char.class)    { return new VarHandleChars.FieldInstanceReadWrite(recv, off); }
        if (type == short.class)   { return new VarHandleShorts.FieldInstanceReadWrite(recv, off); }
        if (type == float.class)   { return new VarHandleFloats.FieldInstanceReadWrite(recv, off); }
        if (type == double.class)  { return new VarHandleDoubles.FieldInstanceReadWrite(recv, off); }
        return new VarHandleReferences.FieldInstanceReadWrite(recv, off, type);
    }

    /**
     * A VarHandle viewing a {@code byte[]} as an array of a WIDER primitive -- stock's own
     * {@code byteArrayViewVarHandle}, which this name-winning overlay had silently dropped.
     *
     * <p>WHY IT MATTERS, measured rather than asserted: every multi-byte read on a stock
     * {@code java.io.DataInputStream} goes {@code readInt -> jdk.internal.util.ByteArray.getInt -> } one of
     * these handles, and {@code ByteArray.<clinit>} builds all six through this method. Without it
     * {@code DataInputStream.readInt()} HALTED THE VM -- confirmed on QEMU before this was written:
     * {@code LINK FAILED: java/lang/invoke/MethodHandles.byteArrayViewVarHandle(...)} followed by a
     * {@code DENYLIST TRAP} blaming a list {@code MethodHandles} is not even on, which is the
     * overlay-drops-stock-members trap this file records more than any other. {@code DataOutputStream}'s
     * {@code write*} family and {@code jdk.internal.util.ByteArrayLittleEndian} go the same way.
     *
     * <p>THE SURFACE IS TWO METHODS, AND THAT IS READ OFF THE SHIPPED CLASS RATHER THAN ASSUMED.
     * {@code javap -s -p} on each generated {@code ArrayHandle} lists exactly {@code index}, {@code get} and
     * {@code set}: an unaligned {@code byte[]} view has NO atomic access modes at all -- every
     * {@code getVolatile}/{@code compareAndSet}/{@code getAndAdd} in the template belongs to the sibling
     * {@code ByteBufferHandle}, which nothing here reaches. So unlike the field handles there is no
     * half-working atomic half to state a limit about; the whole surface either works or does not.
     *
     * <p>NO {@code maybeAdapt}, deliberately: stock wraps the result in one, and that method returns its
     * argument unchanged unless the {@code VAR_HANDLE_IDENTITY_ADAPT} debug property is set. Its adapting
     * arm needs {@code filterValue}/{@code MethodHandles.identity}, i.e. the MethodHandle runtime this VM
     * does not carry -- so copying it would add an unreachable path that could only ever trap.
     *
     * <p>WHY THE OUTER CLASS'S {@code <clinit>} IS NOT NEEDED, which is what keeps this small. Those classes
     * hold three statics -- {@code NIO_ACCESS} (via {@code SharedSecrets.getJavaNioAccess()}),
     * {@code SCOPED_MEMORY_ACCESS} and {@code ALIGN} -- and the first two serve only the ByteBuffer handle.
     * {@code ALIGN} is the one an {@code ArrayHandle} reads, in its bounds check, and it is
     * {@code Integer.BYTES - 1}: a compile-time constant expression (JLS 15.28), so javac INLINES it.
     * Verified with {@code javap -c}, not inferred -- {@code index} opens
     * {@code iload_1; aload_0; arraylength; iconst_3; isub}, with no {@code getstatic ALIGN} anywhere. So the
     * whole {@code VarHandleByteArray*} family is {@code clinitBlocked} and nothing on the access path reads
     * a static of it.
     *
     * @throws IllegalArgumentException      if {@code viewArrayClass} is not an array (stock's exception)
     * @throws UnsupportedOperationException if its component is not one of short/char/int/long/float/double
     */
    public static VarHandle byteArrayViewVarHandle(Class<?> viewArrayClass, ByteOrder byteOrder)
    {
        Objects.requireNonNull(byteOrder);
        return forByteArrayView(viewArrayClass, byteOrder == ByteOrder.BIG_ENDIAN);
    }

    /**
     * Stock {@code VarHandles.byteArrayViewHandle}, one branch per generated class.
     *
     * <p>{@code byte} and {@code boolean} are ABSENT ON PURPOSE and that is stock's behaviour, not a gap: a
     * one-byte view of a {@code byte[]} has nothing to do, so the JDK generates no such {@code ArrayHandle}
     * and throws here. Answering a plausible handle instead would be the silent-wrong-answer shape rule 3
     * exists to remove.
     *
     * <p>{@code getComponentType()} IS LOAD-BEARING, and it only started working one increment ago: it
     * answered NULL for every primitive array until 2026-09-30, which is also what made
     * {@code Unsafe.arrayIndexScale(byte[].class)} answer 8. So this factory was not implementable before
     * that fix, whatever the dispatch could do.
     */
    private static VarHandle forByteArrayView(Class<?> viewArrayClass, boolean be)
    {
        if (!viewArrayClass.isArray())
        {
            throw new IllegalArgumentException("not an array: " + viewArrayClass);
        }
        Class<?> c = viewArrayClass.getComponentType();
        if (c == long.class)   { return new VarHandleByteArrayAsLongs.ArrayHandle(be); }
        if (c == int.class)    { return new VarHandleByteArrayAsInts.ArrayHandle(be); }
        if (c == short.class)  { return new VarHandleByteArrayAsShorts.ArrayHandle(be); }
        if (c == char.class)   { return new VarHandleByteArrayAsChars.ArrayHandle(be); }
        if (c == double.class) { return new VarHandleByteArrayAsDoubles.ArrayHandle(be); }
        if (c == float.class)  { return new VarHandleByteArrayAsFloats.ArrayHandle(be); }
        throw new UnsupportedOperationException();
    }
}
