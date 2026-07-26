package demo;

import magic.Magic;

/**
 * The invokedynamic slice 1c proof: lambdas. Each `() -> ...` compiles to an
 * {@code invokedynamic LambdaMetafactory.metafactory : (captures)Ljava/lang/Runnable;}, which the metal
 * JIT intrinsifies into a synthetic lambda class — a heap object holding the captures whose itable maps
 * {@code Runnable.run()} to a thunk that loads the captures and tail-calls the lambda body. So calling
 * {@code r.run()} dispatches (via the normal itable path) into the body. Exercises: a no-capture lambda,
 * a capturing lambda, and a lambda passed to a method and invoked repeatedly.
 */
public class LambdaDemo
{
    public static void main()
    {
        Runnable a = () -> Magic.printStr("lambda A ran\n");    // no capture
        a.run();

        int who = 5;
        Runnable b = () -> Magic.report(who, 2);                // captures `who` -> "P5 EATS"
        b.run();

        twice(() -> Magic.printStr("twice\n"));                 // lambda passed as an arg, invoked 2x
    }

    static void twice(Runnable r)
    {
        r.run();
        r.run();
    }
}
