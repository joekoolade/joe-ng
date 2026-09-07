import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.net.URL;

/**
 * Both arms of {@code ClassLoader.getResources} on metal -- the ABSENT one, which must answer an empty
 * enumeration silently, and the PRESENT one, which must report that it cannot serve the resource.
 *
 * <p>The absent arm is what unblocked the console launcher: JUnit's
 * {@code LauncherConfigurationParameters.findConfigFile} does {@code Collections.list(cl.getResources(name))}
 * and returns null on an empty list, so its caller skips loading {@code junit-platform.properties}. That is
 * the correct outcome here because the jar genuinely has no such entry.
 *
 * <p>The present arm exists because it would otherwise ship UNTESTED. An always-empty answer would be a
 * silent lie for a resource the jar does hold -- and this jar holds several, starting with
 * {@code META-INF/services/org.junit.platform.engine.TestEngine}, which names the three test engines. The
 * probe asserts the enumeration is still empty (we cannot build a URL) AND that the run said so out loud.
 */
public class ResourceProbe
{
    public static void main(String[] args) throws Exception
    {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        System.out.println("classloader nonNull = " + (cl != null ? 1 : 0) + " (want 1)");

        // ABSENT: exactly what the launcher asks for, and the reason it now gets past LauncherFactory.
        Enumeration<URL> absent = cl.getResources("junit-platform.properties");
        System.out.println("absent nonNull      = " + (absent != null ? 1 : 0) + " (want 1)");
        List<URL> al = Collections.list(absent);
        System.out.println("absent size         = " + al.size() + " (want 0)");

        // PRESENT: the jar really does carry this entry. Expect a report line above, and still an empty list.
        System.out.println("-- expect a 'not served' line next --");
        Enumeration<URL> present = cl.getResources("META-INF/services/org.junit.platform.engine.TestEngine");
        List<URL> pl = Collections.list(present);
        System.out.println("present size        = " + pl.size() + " (want 0, cannot build a URL)");

        // getResource, the single-resource form, on the same two names.
        System.out.println("getResource(absent) = " + (cl.getResource("no/such/thing") == null ? "null" : "NON-NULL <== BUG") + " (want null)");
        System.out.println("-- expect a second 'not served' line next --");
        System.out.println("getResource(present)= " + (cl.getResource("META-INF/services/org.junit.platform.engine.TestEngine") == null ? "null" : "NON-NULL <== BUG") + " (want null)");

        System.out.println("ResourceProbe done");
    }
}
