package java.util.function;

/**
 * Minimal generic {@code BiFunction} — a two-argument {@link Function}. Only the SAM ({@code apply(T,U)->R}) is
 * provided; the JDK's default {@code andThen} is omitted until something needs it. {@code BinaryOperator<T>}
 * extends {@code BiFunction<T,T,T>}, so a {@code BinaryOperator} lambda IS a {@code BiFunction} — which is what
 * lets stock {@code Stream.reduce}/{@code ReduceOps} invoke the accumulator through {@code BiFunction.apply}.
 */
public interface BiFunction<T, U, R>
{
    R apply(T t, U u);
}
