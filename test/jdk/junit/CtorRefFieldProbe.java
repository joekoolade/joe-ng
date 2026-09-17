/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-14
 */
/**
 * A CONSTRUCTOR REFERENCE whose constructor copies a static into an instance field -- the launcher's
 * remaining failure, reduced from the jar's own bytecode rather than from its symptom.
 *
 * <p>WHAT javap SAYS, and why every earlier probe missed it. The NPE's innermost frame is
 * {@code ReflectiveInterceptorCall.lambda$ofVoidMethod$0}, whose body is "load the capture, load the four
 * SAM arguments, invokeinterface on the capture" -- so the CAPTURE is null. The capture is
 * {@code TestMethodTestDescriptor.this.interceptorCall}, an instance field assigned in exactly one place:
 *
 * <pre>
 *   public TestMethodTestDescriptor(UniqueId, Class, Method, Supplier, JupiterConfiguration) {
 *       super(...);
 *       this.interceptorCall = defaultInterceptorCall;      // getstatic + putfield
 *   }
 * </pre>
 *
 * <p>and that constructor is never reached by a {@code new}. {@code MethodSelectorResolver$MethodType}
 * carries {@code REF_newInvokeSpecial TestMethodTestDescriptor.<init>:(...)V} -- a KIND-8 CONSTRUCTOR
 * REFERENCE with a FIVE-argument SAM. joe-ng builds a hand-emitted thunk for that: frame, save the SAM args,
 * {@code Heap.alloc}, store the TIB, restore the args, {@code bl <init>}, return the object.
 *
 * <p>THE MEASURED FACT THIS EXPLAINS AND NO CELL THEORY DOES: with STATIC_ADDR_LOG on, the class's
 * {@code <clinit>} logs {@code ownstatic ...defaultInterceptorCall} for its {@code putstatic}, and the
 * constructor's {@code getstatic} of THE SAME FIELD IN THE SAME CLASS logs nothing on any of the three
 * resolution paths ({@code ownstatic}, {@code staticaddr}, {@code patchstatic}). A static cell that resolved
 * wrongly would still have logged. A constructor body that never runs -- or that is entered with its
 * arguments in the wrong registers -- executes no {@code getstatic} at all, which is the silence observed.
 *
 * <p>SO THE ARMS SEPARATE THE THREE THINGS THAT CAN BE WRONG, because they need opposite fixes:
 * <ul>
 *   <li>{@code direct} -- an ordinary {@code new}. The control. If this fails the constructor itself is
 *       broken and the reference is innocent.</li>
 *   <li>{@code ctorref5} -- the same constructor reached through a five-argument constructor reference.
 *       A thunk that saves or restores the wrong registers passes the low-arity cases every other demo in
 *       this tree uses and fails here.</li>
 *   <li>{@code overloads} -- the class declares THREE constructors, two of them five-argument, exactly as
 *       TestMethodTestDescriptor does. Resolution that matched on class+name+arity rather than the full
 *       descriptor would pick a sibling whose last parameter is the interceptor itself, leaving the field
 *       null with no {@code getstatic} executed -- the same silence from a different cause.</li>
 *   <li>{@code captured} -- the field then travels the launcher's own route: read by {@code getfield},
 *       handed to a STATIC METHOD ON AN INTERFACE, captured by a lambda whose implementation is a private
 *       static interface method (tag-11 InterfaceMethodref, kind 6, nc=1, samArgc=4), and invoked.</li>
 * </ul>
 *
 * <p>Each arm reports the FIELD separately from the call, because "the field is null" and "the capture was
 * lost on the way" are different bugs that both surface as an NPE inside the callee.
 */
public class CtorRefFieldProbe
{
    /** The unbound interface method reference's target -- InvocationInterceptor's role. */
    interface Interceptor
    {
        String intercept(String a, String b, String c);
    }

    /** VoidMethodInterceptorCall's role: four SAM arguments, the first of them the receiver. */
    interface Call4
    {
        void apply(Interceptor i, String a, String b, String c);
    }

    /** ReflectiveInterceptorCall's role: a nested INTERFACE carrying a static factory and a lambda body. */
    interface Wrapper
    {
        Object apply(Interceptor i, String a, String b, String c);

        /**
         * ofVoidMethod exactly: the factory is a static method on an interface, so the synthetic body is a
         * PRIVATE STATIC INTERFACE METHOD and its bootstrap handle is a tag-11 InterfaceMethodref.
         */
        static Wrapper ofVoid(Call4 call)
        {
            return (i, a, b, c) ->
            {
                call.apply(i, a, b, c);
                return null;
            };
        }
    }

    /** The five-argument SAM the constructor reference is bound to. */
    interface Factory5
    {
        Descriptor make(String id, String name, String cls, String method, String config);
    }

    /** A second five-argument SAM over the SIBLING constructor, so both overloads are actually reachable. */
    interface Factory5b
    {
        Descriptor makeWith(String id, String name, String cls, String method, Call4 call);
    }

    static String seen = "<never called>";

    static void record(Interceptor i, String a, String b, String c)
    {
        seen = i.intercept(a, b, c);
    }

    /**
     * TestMethodTestDescriptor's shape: a {@code <clinit>} that stores a kind-9 unbound interface method
     * reference into a static, three constructors, and an instance field copied from that static by exactly
     * one of them.
     */
    static class Descriptor
    {
        /** defaultInterceptorCall's role: set by <clinit> from an indy, read by the 5-arg constructor. */
        private static final Call4 DEFAULT_CALL;

        /** A NON-indy store beside it: the control that says the initializer ran at all. */
        static final String MARKER;

        static
        {
            MARKER = "set";
            DEFAULT_CALL = CtorRefFieldProbe::record;
        }

        final String id;
        final String name;
        private final Call4 call;

        /** THE ONE THE CONSTRUCTOR REFERENCE NAMES: five arguments, and the field comes from the static. */
        Descriptor(String id, String name, String cls, String method, String config)
        {
            this.id = id + "/" + cls + "/" + method + "/" + config;
            this.name = name;
            this.call = DEFAULT_CALL;
        }

        /** The sibling with the SAME ARITY whose last parameter IS the call -- the wrong-overload trap. */
        Descriptor(String id, String name, String cls, String method, Call4 call)
        {
            this.id = id + "/" + cls + "/" + method;
            this.name = name;
            this.call = call;
        }

        /** A third constructor, as TestMethodTestDescriptor has, so arity alone cannot discriminate. */
        Descriptor(String id, String name)
        {
            this.id = id;
            this.name = name;
            this.call = DEFAULT_CALL;
        }

        boolean hasCall()
        {
            return call != null;
        }

        /** invokeTestMethod's route: getfield, then through the interface-static factory and back. */
        String run(Interceptor i)
        {
            Wrapper w = Wrapper.ofVoid(this.call);
            w.apply(i, "a", "b", "c");
            return seen;
        }
    }


    /**
     * THE CONDITION, WHICH THE ARMS ABOVE DO NOT REPRODUCE: a class whose ONLY touch is a constructor
     * reference.
     *
     * <p>Every arm above prints {@code Descriptor.MARKER} first, and a {@code getstatic} is a JVMS 5.5
     * active use -- so those arms initialize the class BEFORE the reference fires, and would pass even if a
     * constructor reference initialized nothing. That is the same trap already recorded twice in this tree
     * (LambdaThreadDemo tested {@code new Thread(lambda)} but never a receiver whose directory lacked the
     * entry; DefaultIfaceDemo passed with the fix REVERTED).
     *
     * <p>JVMS 5.5 lists instance creation as an active use, so {@code Lonely.<clinit>} MUST have run before
     * the reference hands back an object. joe-ng's kind-8 thunk allocates, stores the TIB and calls
     * {@code <init>} -- and reading it, nothing on that path calls {@code ensureClinit}. For an ordinary
     * {@code new} the compiler notes the initialization; a constructor REFERENCE defers the instantiation to
     * whenever the SAM is invoked, which is exactly the hole a deferred {@code new} fell into once before.
     *
     * <p>An uninitialized class here is SILENT: the constructor runs, reads a static that is still null, and
     * the field is null for the life of the object -- which is what the launcher reports, a dozen frames
     * away, as an NPE on a captured value.
     */
    static class Lonely
    {
        static final Call4 LONELY_CALL;
        static final String LONELY_MARKER;

        static
        {
            LONELY_MARKER = "ran";
            LONELY_CALL = CtorRefFieldProbe::record;
        }

        final Call4 call;
        final String id;

        Lonely(String id, String name, String cls, String method, String config)
        {
            this.id = id + "/" + cls + "/" + method + "/" + config;
            this.call = LONELY_CALL;
        }

        boolean hasCall()
        {
            return call != null;
        }
    }

    interface LonelyFactory
    {
        Lonely make(String id, String name, String cls, String method, String config);
    }


    /**
     * THE CONDITION THE {@code Lonely} ARM STILL MISSED -- and why it passed before the fix that made it
     * necessary.
     *
     * <p>{@code Lonely} looked right: touched only through a constructor reference, its {@code <clinit>}
     * DEFERRED at batch time and compiled late (COMPILE_WATCH confirms both). It passed anyway, because
     * {@code main} goes on to read {@code Lonely.LONELY_MARKER} -- and compiling a CROSS-CLASS
     * {@code getstatic} calls {@code noteInitNeeded}, so the drain initialized the class before main executed
     * a single instruction. The probe initialized its own target as a side effect of checking it.
     *
     * <p>That is exactly the launcher's difference: {@code MethodSelectorResolver$MethodType} holds a
     * {@code TestMethodTestDescriptor::new} and reads NONE of its statics, so nothing notes its
     * initialization and the constructor reference is genuinely the first active use.
     *
     * <p>So this class's statics are read NOWHERE in this program. Every assertion goes through an INSTANCE
     * method, which cannot note anything. Without {@code ensureClinit} on the kind-8 path, {@code MARK} is
     * still null when {@code <init>} copies it, and {@code hasMark()} answers false.
     */
    static class Untouched
    {
        private static final String MARK;

        static
        {
            MARK = "clinit-ran";
        }

        private final String mark;
        final int id;

        Untouched(int id)
        {
            this.id = id;
            this.mark = MARK;
        }

        boolean hasMark()
        {
            return mark != null;
        }

        String mark()
        {
            return mark == null ? "<null: clinit did not run before <init>>" : mark;
        }
    }

    interface UntouchedFactory
    {
        Untouched make(int id);
    }

    static class Upper implements Interceptor
    {
        public String intercept(String a, String b, String c)
        {
            return a + b + c;
        }
    }

    public static void main(String[] args)
    {
        // The initializer ran at all: a non-indy store beside the indy one.
        System.out.println("clinit marker = " + Descriptor.MARKER + " (want set)");

        // CONTROL: an ordinary `new` of the very same constructor. If this fails, the constructor is the
        // problem and the reference is innocent.
        Descriptor direct = new Descriptor("i", "n", "C", "m", "cfg");
        System.out.println("direct id = " + direct.id + " (want i/C/m/cfg)");
        System.out.println("direct hasCall = " + direct.hasCall() + " (want true)");

        // THE LAUNCHER'S ROUTE: a kind-8 constructor reference with a FIVE-argument SAM.
        Factory5 f = Descriptor::new;
        Descriptor viaRef = f.make("i", "n", "C", "m", "cfg");
        System.out.println("ctorref5 id = " + viaRef.id + " (want i/C/m/cfg)");
        System.out.println("ctorref5 name = " + viaRef.name + " (want n)");
        System.out.println("ctorref5 hasCall = " + viaRef.hasCall() + " (want true)");

        // The SIBLING five-argument constructor, reached through its own reference. Both overloads must be
        // distinguishable by DESCRIPTOR: if the thunk resolves on class+name+arity it will pick one of these
        // for both references, and exactly one of the two arms then reports the other's shape.
        Factory5b fb = Descriptor::new;
        Descriptor viaRefB = fb.makeWith("j", "o", "D", "p", CtorRefFieldProbe::record);
        System.out.println("ctorref5b id = " + viaRefB.id + " (want j/D/p)");
        System.out.println("ctorref5b hasCall = " + viaRefB.hasCall() + " (want true)");

        // THE WHOLE CHAIN, which is where the launcher's NPE surfaces: the field read by getfield, handed to
        // a static method on an interface, captured by a kind-6 lambda with four SAM arguments, invoked.
        seen = "<never called>";
        System.out.println("direct captured = " + direct.run(new Upper()) + " (want abc)");

        seen = "<never called>";
        System.out.println("ctorref captured = " + viaRef.run(new Upper()) + " (want abc)");

        seen = "<never called>";
        System.out.println("ctorrefB captured = " + viaRefB.run(new Upper()) + " (want abc)");

        // The two-argument constructor through a reference as well, so a thunk that only handles small
        // arities is separated from one that handles none.
        Descriptor small = new Descriptor("s", "t");
        System.out.println("2arg hasCall = " + small.hasCall() + " (want true)");

        // THE CONDITION: Lonely is named NOWHERE else in this program -- no getstatic, no `new`, no method
        // call -- so the constructor reference is its first and only active use. If joe-ng's kind-8 thunk
        // does not initialize the class, LONELY_CALL is still null when <init> reads it and the field is null
        // with no getstatic having been executed anywhere. That is the launcher's exact silence.
        LonelyFactory lf = Lonely::new;
        Lonely lonely = lf.make("k", "l", "E", "q", "cfg2");
        System.out.println("lonely id = " + lonely.id + " (want k/E/q/cfg2)");
        System.out.println("lonely hasCall = " + lonely.hasCall() + " (want true)");
        System.out.println("lonely clinit ran = " + Lonely.LONELY_MARKER + " (want ran)");

        // THE NEGATIVE-CONTROL ARM: Untouched's statics are read nowhere, so the constructor reference is
        // the first active use and nothing else can have initialized it. This is the arm that fails with the
        // kind-8 ensureClinit removed.
        UntouchedFactory uf = Untouched::new;
        Untouched u = uf.make(7);
        System.out.println("untouched id = " + u.id + " (want 7)");
        System.out.println("untouched hasMark = " + u.hasMark() + " (want true)");
        System.out.println("untouched mark = " + u.mark() + " (want clinit-ran)");

        System.out.println("CtorRefFieldProbe done");
    }
}
