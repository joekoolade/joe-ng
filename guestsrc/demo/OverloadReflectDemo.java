package demo;

/**
 * Overloaded methods under {@code Class.getDeclaredMethods()}.
 *
 * <p>A class may declare two methods with the SAME NAME, and then the name alone identifies neither. This VM
 * enumerated declared methods by name and resolved each by name, so an overloaded class produced the same
 * {@code Method} object twice over and the other overload was unreachable -- its parameter count, its
 * annotations and its body alike. The shape that found it is a stock {@code @ParameterizedTest}: the test
 * method and its same-named {@code @MethodSource} factory, where the factory was invisible and the test's own
 * annotation was reported for both.
 *
 * <p>The arms are ordered so a partial fix cannot pass. Distinct parameter counts prove the enumeration
 * separated the overloads; invoking each proves resolution reached the RIGHT body, not merely three distinct
 * wrappers onto one. The overloads differ by ARITY on purpose -- telling two same-arity overloads apart needs
 * {@code getParameterTypes}, which this overlay does not have.
 */
public class OverloadReflectDemo
{
    static String pick()
    {
        return "none";
    }

    static String pick(int a)
    {
        return "int:" + a;
    }

    static String pick(int a, int b)
    {
        return "two:" + (a + b);
    }

    public static void main(String[] args) throws Exception
    {
        java.lang.reflect.Method[] all = OverloadReflectDemo.class.getDeclaredMethods();
        int found = 0;
        int[] counts = new int[4];
        int i = 0;
        while (i < all.length)
        {
            if (all[i].getName().equals("pick"))
            {
                found += 1;
                counts[all[i].getParameterCount()] += 1;
            }
            i += 1;
        }
        System.out.println("  overloads named pick = " + found + " (want 3)");
        System.out.println("  by parameter count 0/1/2 = " + counts[0] + "/" + counts[1] + "/" + counts[2]
                + " (want 1/1/1)");

        String zero = "?";
        String one = "?";
        String two = "?";
        i = 0;
        while (i < all.length)
        {
            java.lang.reflect.Method m = all[i];
            if (m.getName().equals("pick"))
            {
                if (m.getParameterCount() == 0)
                {
                    zero = (String) m.invoke(null, new Object[0]);
                }
                else if (m.getParameterCount() == 1)
                {
                    one = (String) m.invoke(null, new Object[] { Integer.valueOf(7) });
                }
                else
                {
                    two = (String) m.invoke(null, new Object[] { Integer.valueOf(20), Integer.valueOf(22) });
                }
            }
            i += 1;
        }
        System.out.println("  invoked = " + zero + " " + one + " " + two + " (want none int:7 two:42)");
    }
}
