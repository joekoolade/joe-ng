package demo;

import java.lang.reflect.Method;

/**
 * Probe for the {@code Class.forName} counterpart of the {@code defineClass} vtable hole fixed in PR #170.
 * {@code loadClassIncremental} seeds reachability from the class's {@code <clinit>} alone, so a class without
 * one has every method pruned by RTA — reflection into it still works (that resolves through the method
 * registry), but a VIRTUAL call inside it should find a zero slot.
 *
 * <p>{@code app.Greeting} lives only in {@code /lib/app.jar}, named by {@code /etc/init}'s {@code classpath=},
 * so {@code forName} reaches it through the same jar the loader uses. {@code consonants()} dispatches
 * {@code text()} virtually on itself — the exact shape that faulted before #170 on the defineClass path.
 */
public class ForNameVirtualDemo
{
    public static void main(String[] args) throws Exception
    {
        Class<?> c = Class.forName("app.Greeting");
        System.out.println("forName loaded " + c.getName());

        Object g = c.getDeclaredConstructor(String.class).newInstance("forName");
        Method text = c.getDeclaredMethod("text");
        System.out.println("text() = " + text.invoke(g));           // reflection: goes via the method registry

        Method consonants = c.getDeclaredMethod("consonants");
        System.out.println("consonants() = " + consonants.invoke(g));   // inside: a VIRTUAL call to text()

        // Closure stress. Stubbing a class's virtuals must pull NOTHING: the earlier attempt at this fix
        // seeded them reachable instead and "pulled a huge closure into the 2nd (incremental) batch and
        // corrupted the heap". java.util.regex.Pattern is the canonical big one -- naming it at runtime is
        // the case that failed. A deferral stub is a few instructions and drags in no dependencies.
        Class<?> pat = Class.forName("java.util.regex.Pattern");
        System.out.println("forName loaded " + pat.getName());
        Class<?> sb = Class.forName("java.lang.StringBuilder");
        System.out.println("forName loaded " + sb.getName());
    }
}
