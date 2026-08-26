package demo;

/**
 * Forces java/lang/StrictMath's lazy {@code <clinit>} -- the point where the stock jtreg test
 * jar/Attributes/PutAndPutAll wedges (its last log line is {@code clinit-lazy java/lang/StrictMath},
 * then no further output).
 *
 * <p>StrictMath's initializer is nothing but the javac assertions idiom:
 * {@code ldc StrictMath.class; invokevirtual Class.desiredAssertionStatus; putstatic $assertionsDisabled}.
 */
public final class StrictMathDemo
{
    public static void main(String[] args)
    {
        System.out.println("before StrictMath");
        double a = StrictMath.abs(-2.5);
        System.out.println("StrictMath.abs(-2.5) = " + (int) a);
        System.out.println("done");
    }
}
