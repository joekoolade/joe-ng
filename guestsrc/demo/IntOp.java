package demo;

/** A functional interface whose SAM takes an argument (unlike Runnable) — exercises slice-1d lambdas. */
public interface IntOp
{
    int apply(int x);
}
