package demo;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code Map.of} / {@code Map.copyOf} on metal — the immutable-map path
 * ({@code java.util.ImmutableCollections.MapN}) that {@code java.util.jar.Attributes$Name}'s initializer runs
 * through, and the regression guard for the two bugs that blocked it: {@code MapN.probe} indexes its table
 * with {@code Math.floorMod(key.hashCode(), table.length >> 1)}, and {@code String.hashCode} overflows by
 * construction, so an int that did not stay sign-extended made {@code floorMod} return a NEGATIVE index
 * (see {@link DivDemo}); {@code Map.copyOf} also casts a {@code Map.Entry[]} to {@code Object[]}, which needs
 * array covariance to accept an INTERFACE element type.
 */
public class MapCopyDemo
{
    public static void main(String[] args)
    {
        HashMap<String, String> src = new HashMap<>();
        src.put("Manifest-Version", "1.0");
        src.put("Main-Class", "app.Main");
        src.put("Created-By", "joe-ng");
        System.out.println("src size=" + src.size());

        Object[] entries = src.entrySet().toArray(new Map.Entry[0]);   // Map.Entry[] -> Object[] covariance
        System.out.println("entries=" + entries.length);               // 3

        Map<String, String> one = Map.of("a", "b");
        System.out.println("Map.of size=" + one.size() + " get(a)=" + one.get("a"));

        Map<String, String> copy = Map.copyOf(src);                    // -> ImmutableCollections.MapN
        System.out.println("copyOf size=" + copy.size()
                + " " + copy.get("Manifest-Version")
                + " " + copy.get("Main-Class")
                + " " + copy.get("Created-By"));                       // 3 1.0 app.Main joe-ng
        System.out.println("copyOf missing=" + copy.get("Nope"));      // null
    }
}
