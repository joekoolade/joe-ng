package demo;

/**
 * A failed {@code checkcast} must throw {@link ClassCastException}, not spin. Until this demo, VM.checkCast
 * ended a failed cast in {@code while (true) Magic.wfe()} -- which is why the stock jtreg test
 * jar/Attributes/PutAndPutAll hung: its very first action is a deliberately-failing cast.
 */
public final class CastDemo
{
    public static void main(String[] args)
    {
        Object str = "hello";
        Object num = Integer.valueOf(7);

        System.out.println("good cast = " + (String) str);

        try
        {
            String bad = (String) num;
            System.out.println("FAIL: no throw, got " + bad);
        }
        catch (ClassCastException e)
        {
            System.out.println("bad cast threw ClassCastException");
        }

        Object nul = null;
        String n = (String) nul;                       // null casts to anything
        System.out.println("null cast ok = " + (n == null));

        try
        {
            Object o = new int[3];
            String s = (String) o;
            System.out.println("FAIL: array->String did not throw " + s);
        }
        catch (ClassCastException e)
        {
            System.out.println("array cast threw ClassCastException");
        }

        // A CCE is a RuntimeException, so a broader handler must catch it too -- proves the thrown object
        // carries a real TIB and its Type chain walks (a TIB-less object would be uncatchable).
        try
        {
            Object o = num;
            System.out.println("FAIL " + (String) o);
        }
        catch (RuntimeException e)
        {
            System.out.println("caught as RuntimeException = " + (e instanceof ClassCastException));
        }

        int deep = 0;
        try
        {
            deep = level1(num);
        }
        catch (ClassCastException e)
        {
            deep = 42;                                  // thrown two frames down, caught here -> real unwind
        }
        System.out.println("cross-frame unwind = " + deep);
        System.out.println("done");
    }

    private static int level1(Object o)
    {
        return level2(o);
    }

    private static int level2(Object o)
    {
        String s = (String) o;
        return s.length();
    }
}
