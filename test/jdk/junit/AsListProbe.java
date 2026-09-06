import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Does {@code Arrays.asList} return a usable list on metal?
 *
 * <p>picocli's {@code ColorScheme.Builder.commands(IStyle...)} does
 * {@code commandStyles.addAll(Arrays.asList(styles))} and NPEs INSIDE {@code ArrayList.addAll} -- inside, so
 * the receiver is a real list and the ARGUMENT is null. That points at asList itself, and asList is used all
 * over picocli's parse path, so a null from it would explain failures far from where it is called.
 *
 * <p>No picocli here on purpose: this is a small closure that boots fast, and if it reproduces then the whole
 * console-launcher chase reduces to one java.util method.
 */
public class AsListProbe
{
    public static void main(String[] args)
    {
        Object[] arr = new Object[] { "a", "b", "c" };
        List<Object> l = Arrays.asList(arr);
        System.out.println("asList(Object[3]) = " + (l == null ? "NULL  <== BUG" : "non-null size=" + l.size()));

        List<String> v = Arrays.asList("x", "y");
        System.out.println("asList(varargs 2)  = " + (v == null ? "NULL  <== BUG" : "non-null size=" + v.size()));

        List<String> e = Arrays.asList();
        System.out.println("asList(empty)      = " + (e == null ? "NULL  <== BUG" : "non-null size=" + e.size()));

        // The exact shape picocli uses: addAll from an asList result into a real ArrayList.
        try
        {
            ArrayList<Object> target = new ArrayList<>();
            target.addAll(Arrays.asList(arr));
            System.out.println("addAll(asList)     = OK size=" + target.size());
        }
        catch (Throwable t)
        {
            System.out.println("addAll(asList)     = THREW " + t.getClass().getName() + "  <== reproduces");
        }
    }
}
