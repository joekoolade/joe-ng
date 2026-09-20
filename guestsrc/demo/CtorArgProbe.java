/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-16
 */
package demo;

/**
 * A `new` built MID-EXPRESSION and handed straight to a {@code this(...)} call arrives as NULL two frames
 * down -- picocli's shape, reduced. On metal the console launcher dies with
 * {@code NullPointerException: factory} at {@code CommandUserObject.create}; the HOST JVM runs the identical
 * jar and command line without complaint, so the fault is this VM's.
 *
 * <p>The bytecode being reproduced, from {@code CommandLine.<init>(Object)}:
 * <pre>
 *   0: aload_0                 // this
 *   1: aload_1                 // command
 *   2: new  DefaultFactory     // &lt;- must stay live across the nested &lt;init&gt; below
 *   5: dup
 *   6: aconst_null
 *   7: invokespecial DefaultFactory.&lt;init&gt;(CommandLine$1)V
 *  10: invokespecial CommandLine.&lt;init&gt;(Object, IFactory)V
 * </pre>
 * At offset 7 the operand stack is {@code [this, command, newref, newref, null]}; the nested constructor
 * consumes two and {@code newref} must survive at depth 2. **That is the operand-live-across-a-call case**,
 * which this VM has been bitten by twice -- "operand values spill to the frame across calls so
 * mid-expression calls (e.g. `new X()`'s constructor) don't clobber live refs", and the later
 * `opSlot lost a live operand value`.
 *
 * <p>WHAT MAKES IT A PROBE RATHER THAN A DEMO: it reports EVERY argument at EVERY frame, not just the one
 * that throws. `command` (slot 0) surviving while `factory` (slot 1) does not is the whole discriminator --
 * it separates "the reference was lost in transit" from "the object was never built", and a probe that only
 * checked the crashing value could not tell those apart. The lambda-capture arc needed exactly this.
 *
 * <p>AND IT RECORDS RATHER THAN PRINTS. A {@code println} inside the chain adds a call and changes the
 * operand depth of the very frames under test; this VM has already had a bug VANISH when an instrument was
 * added. Each frame writes a boolean to a static and the report is printed afterwards, from outside.
 *
 * <p>Arm B is the control that makes arm A mean something: the identical chain, with the factory coming
 * from a CALL instead of an inline {@code new}. If A fails and B passes, the fault is the mid-expression
 * {@code new} and not argument passing in general.
 */
public class CtorArgProbe
{
    /** Like picocli's synthetic {@code CommandLine$1}: the factory constructor takes one and ignores it. */
    static final class Marker { }

    interface IFactory { String tag(); }

    static final class DefaultFactory implements IFactory
    {
        DefaultFactory(Marker ignored) { }
        public String tag() { return "df"; }
    }

    /** The control's source: same object, reached through a call rather than an inline `new`. */
    static IFactory make() { return new DefaultFactory(null); }

    // Per-frame observations. Statics, not prints: see the class comment.
    static boolean c3cmd, c3fac;       // the private 3-arg constructor
    static boolean fa1cmd, fa1fac;     // forAnnotated
    static boolean ex1cmd, ex1fac;     // extract
    static boolean cr1cmd, cr1fac;     // create

    static void reset()
    {
        c3cmd = false;  c3fac = false;
        fa1cmd = false; fa1fac = false;
        ex1cmd = false; ex1fac = false;
        cr1cmd = false; cr1fac = false;
    }

    // ---- the static chain, mirroring forAnnotatedObject -> extractCommandSpec -> create ----

    static String forAnnotatedObject(Object command, IFactory factory)
    {
        fa1cmd = command != null;
        fa1fac = factory != null;
        return extract(command, factory, true);
    }

    static String extract(Object command, IFactory factory, boolean flag)
    {
        ex1cmd = command != null;
        ex1fac = factory != null;
        return create(command, factory);
    }

    static String create(Object command, IFactory factory)
    {
        cr1cmd = command != null;
        cr1fac = factory != null;
        return factory == null ? "NULL-FACTORY" : factory.tag();
    }

    /** ARM A -- picocli's exact shape: `new` mid-expression, straight into {@code this(...)}. */
    static final class Inline
    {
        final String r;
        Inline(Object command) { this(command, new DefaultFactory(null)); }
        Inline(Object command, IFactory factory) { this(command, factory, true); }
        private Inline(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;
            c3fac = factory != null;
            r = forAnnotatedObject(command, factory);
        }
    }

    /** ARM B -- the control: identical chain, factory from a CALL rather than an inline `new`. */
    static final class ViaCall
    {
        final String r;
        ViaCall(Object command) { this(command, make()); }
        ViaCall(Object command, IFactory factory) { this(command, factory, true); }
        private ViaCall(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;
            c3fac = factory != null;
            r = forAnnotatedObject(command, factory);
        }
    }

    /**
     * ARM C -- the shape PLUS picocli's condition. Its private constructor is fat: several fields, each
     * initialised from its own mid-expression `new`, before the chain is called. picocli's 3-arg constructor
     * does exactly this (colorScheme, executionStrategy, ...), and a deep operand stack is the state in
     * which this VM's spill path has gone wrong before. A probe with the shape and not the condition proves
     * nothing, which this project has paid for more than once.
     */
    static final class Fat
    {
        final String r;
        Object a, b, c, d, e, f, g;
        Fat(Object command) { this(command, new DefaultFactory(null)); }
        Fat(Object command, IFactory factory) { this(command, factory, true); }
        private Fat(Object command, IFactory factory, boolean flag)
        {
            a = new Marker();
            b = new Marker();
            c = new DefaultFactory(null);
            d = new Marker();
            e = new DefaultFactory(null);
            f = new Marker();
            g = new DefaultFactory(null);
            c3cmd = command != null;
            c3fac = factory != null;
            r = forAnnotatedObject(command, factory);
        }
    }

    /** A sink with seven reference parameters: calling it takes the operand stack to 7 -- {@code OP_MAX}. */
    static Object sink7(Object a, Object b, Object c, Object d, Object e, Object f, Object g) { return a; }

    /** picocli's {@code CommandLine.tracer()}: an ordinary static call made BEFORE the factory is passed on. */
    static Object tracer() { return new Marker(); }

    /** Something built FROM the receiver, like picocli's {@code new CommandLine$Interpreter(this)}. */
    static final class Holder
    {
        Holder(Object owner) { }
    }

    /**
     * The CONDITION {@code extractCommandSpec} has and every other frame in the failing chain does not.
     * That method is {@code stack=7, locals=12} -- past {@code Baseline.LOC_MAX} (10), so slots 10 and 11
     * live in frame memory, and exactly at {@code OP_MAX}. It calls {@code tracer()} at bytecode 7 and only
     * then hands {@code aload_1} -- the factory -- to {@code create} at bytecode 38. Reading the trace frame
     * by frame, four of the six frames pass their argument with NO intervening call and so cannot lose it;
     * this is one of the two that can.
     */
    static String wideExtract(Object command, IFactory factory, boolean flag)
    {
        Object t = tracer();                       // a real call, before the hand-off
        if (command == null)
        {
            return "early";                        // the instanceof short-circuit picocli takes first
        }
        ex1cmd = command != null;
        ex1fac = factory != null;                  // slot 1, read AFTER that call
        String r = create(command, factory);
        Object l5 = t;
        Object l6 = t;
        Object l7 = t;
        Object l8 = t;
        Object l9 = t;
        Object l10 = t;
        Object l11 = t;
        sink7(l5, l6, l7, l8, l9, l10, l11);       // seven live operands, and slots 5..11: locals 12
        return r;
    }

    /** ARM D -- the same three-deep chain, through the WIDE extract above. */
    static final class Wide
    {
        final String r;
        Wide(Object command) { this(command, new DefaultFactory(null)); }
        Wide(Object command, IFactory factory) { this(command, factory, true); }
        private Wide(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;
            c3fac = factory != null;
            fa1cmd = command != null;
            fa1fac = factory != null;
            r = wideExtract(command, factory, true);
        }
    }

    /**
     * ARM E -- the OTHER place the reference can be lost. picocli's 3-arg constructor PASSES its own
     * {@code Assert.notNull(factory)} at bytecode 54 (so the factory is alive there), then builds
     * {@code new Interpreter(this)} at 64-69, and reads {@code aload_2} again only at 77. Arm C allocates
     * mid-expression too, but none of its {@code new}s take the RECEIVER -- a different value is live across
     * the call, and that is exactly the kind of difference that has made a probe pass while the program
     * failed.
     */
    static final class LateThis
    {
        final String r;
        Object h1, h2;
        LateThis(Object command) { this(command, new DefaultFactory(null)); }
        LateThis(Object command, IFactory factory) { this(command, factory, true); }
        private LateThis(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;               // picocli's Assert.notNull, which PASSES on the launcher
            c3fac = factory != null;
            h1 = new Holder(this);                 // `new Interpreter(this)`
            h2 = new Holder(this);
            r = forAnnotatedObject(command, factory);    // slot 2, read after those calls
        }
    }

    /**
     * A constructor that forces a DEMAND-LOAD BATCH, which is the condition arm E lacks.
     *
     * <p>Measured on the launcher: between the 3-arg constructor's own {@code Assert.notNull(factory)} at
     * bytecode 54 -- which returns the factory NON-NULL -- and its {@code aload_2} at bytecode 77, which
     * reads NULL, the only call is {@code new CommandLine$Interpreter(this)}. That constructor runs
     * {@code registerBuiltInConverters()}, and the log shows THREE demand-load batches pulling 6,495 blobs
     * inside that 24-bytecode window. Arm E reproduces the bytecode shape with none of that underneath it.
     */
    static final class Loading
    {
        Object m;
        Loading(Object owner)
        {
            m = loadSomething();
        }
    }

    /** Pull a class the closure does not already carry: an incremental load, i.e. a real batch. */
    static Object loadSomething()
    {
        try
        {
            return Class.forName("java.util.regex.Pattern");
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** ARM F -- arm E's shape PLUS the demand-load the launcher does inside the same window. */
    static final class Batch
    {
        final String r;
        Object h1;
        Batch(Object command) { this(command, new DefaultFactory(null)); }
        Batch(Object command, IFactory factory) { this(command, factory, true); }
        private Batch(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;              // picocli's Assert.notNull, which PASSES on the launcher
            c3fac = factory != null;
            h1 = new Loading(this);               // the call that demand-loads, as Interpreter's does
            r = forAnnotatedObject(command, factory);   // slot 2, read after it
        }
    }

    /**
     * ARM G -- the mechanism the launcher bisect points at, reduced.
     *
     * <p>MEASURED on the launcher: local slot 2 of {@code CommandLine.<init>} survives five calls and is
     * destroyed by the sixth, {@code new CommandLine$Interpreter(this)}. That constructor is
     * {@code locals=2}, so it never saves x21 itself; x21 is saved by whichever DEEPER frame uses slot 2 and
     * restored by that frame's epilogue. An exception unwinding PAST such a frame skips its epilogue -- the
     * unwinder pops frames without restoring callee-saved registers -- so the register is never put back and
     * the damage lands on an ANCESTOR that never touched it.
     *
     * <p>The shape is exact: {@code thrower} uses slot 2 (so it saves x21) and throws; {@code middle} and
     * {@code catcher} use two locals or fewer, so neither saves x21 and neither can repair it; and picocli
     * throws as ordinary control flow while loading, which is why this fires there and nowhere in the suite.
     */
    static final class Unwinding
    {
        Unwinding(Object owner)
        {
            catcher();
        }
    }

    static void catcher()
    {
        try
        {
            middle();
        }
        catch (RuntimeException e)
        {
            return;                                   // caught ABOVE the frame that saved x21
        }
    }

    static void middle()
    {
        thrower();
    }

    static void thrower()
    {
        Object a = new Marker();
        Object b = new Marker();
        Object c = new Marker();                      // slot 2 -- this frame SAVES x21, then never restores it
        if (a != null && b != null && c != null)
        {
            throw new RuntimeException();
        }
    }

    /** ARM G's entry: the same three-deep chain, with the unwinding call where picocli's Interpreter is. */
    static final class Unwind
    {
        final String r;
        Object h1;
        Unwind(Object command) { this(command, new DefaultFactory(null)); }
        Unwind(Object command, IFactory factory) { this(command, factory, true); }
        private Unwind(Object command, IFactory factory, boolean flag)
        {
            c3cmd = command != null;
            c3fac = factory != null;
            h1 = new Unwinding(this);                 // a call whose subtree throws and catches
            r = forAnnotatedObject(command, factory); // slot 2, read after it
        }
    }

    static void report(String arm, String r)
    {
        System.out.println(arm + " = " + r);
        System.out.println("  ctor3      command=" + yn(c3cmd)  + " factory=" + yn(c3fac));
        System.out.println("  forAnnot   command=" + yn(fa1cmd) + " factory=" + yn(fa1fac));
        System.out.println("  extract    command=" + yn(ex1cmd) + " factory=" + yn(ex1fac));
        System.out.println("  create     command=" + yn(cr1cmd) + " factory=" + yn(cr1fac));
    }

    static String yn(boolean b) { return b ? "ok" : "NULL"; }

    public static void main(String[] args)
    {
        Object command = new Marker();

        reset();
        report("A inline-new ", new Inline(command).r);     // want df
        reset();
        report("B via-call   ", new ViaCall(command).r);    // want df  (control)
        reset();
        report("C fat+inline ", new Fat(command).r);        // want df
        reset();
        report("D wide-locals", new Wide(command).r);       // want df
        reset();
        report("E late-this  ", new LateThis(command).r);   // want df
        reset();
        report("F demand-load", new Batch(command).r);      // want df
        reset();
        report("G unwind     ", new Unwind(command).r);     // want df

        System.out.println("CtorArgProbe done");
    }
}
