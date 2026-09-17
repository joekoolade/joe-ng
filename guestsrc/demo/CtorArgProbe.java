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

    static String forAnnotated(Object command, IFactory factory)
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
            r = forAnnotated(command, factory);
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
            r = forAnnotated(command, factory);
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
            r = forAnnotated(command, factory);
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

        System.out.println("CtorArgProbe done");
    }
}
