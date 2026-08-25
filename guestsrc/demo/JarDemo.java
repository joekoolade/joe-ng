package demo;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * The stock {@code java.util.jar} API on bare metal, both halves:
 *
 * <ul>
 *   <li><b>Streaming</b> — an UNMODIFIED {@code JarInputStream} over a RAMFS jar, whose {@code getManifest()}
 *       parses the manifest with the stock {@code Manifest}/{@code Attributes} before the first entry.</li>
 *   <li><b>Random access</b> — {@code JarFile} (overlaid onto {@code zip.ZipDir}) looks entries up by name,
 *       reads the manifest, and enumerates the archive.</li>
 *   <li><b>Classes out of the jar</b> — a {@code JarClassLoader} whose {@code findClass} pulls
 *       {@code <name>.class} from the {@code JarFile} and hands the bytes to {@code ClassLoader.defineClass},
 *       then the class is driven by reflection. This is the ordinary Java way to load from an archive, on top
 *       of the stock API, alongside the VM-level {@code classpath=} route the loader itself uses.</li>
 * </ul>
 */
public class JarDemo
{
    public static void main(String[] args) throws Exception
    {
        String path = args.length > 0 ? args[0] : "/lib/app.jar";

        // --- streaming: JarInputStream ---
        InputStream file = new FileInputStream(path);
        JarInputStream jin = new JarInputStream(file, false);   // unsigned jar: skip JarVerifier
        Manifest streamed = jin.getManifest();
        System.out.println("JarInputStream manifest mainClass="
                + (streamed == null ? "none" : streamed.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS)));
        int streamedEntries = 0;
        JarEntry je = jin.getNextJarEntry();
        while (je != null)
        {
            streamedEntries += 1;
            System.out.println("  entry " + je.getName() + " size=" + je.getSize());
            je = jin.getNextJarEntry();
        }
        jin.close();
        System.out.println("JarInputStream entries=" + streamedEntries);

        // --- random access: JarFile ---
        JarFile jar = new JarFile(path);
        System.out.println("JarFile name=" + jar.getName() + " size=" + jar.size());
        Manifest mf = jar.getManifest();
        String mainClass = mf.getMainAttributes().getValue("Main-Class");
        System.out.println("JarFile Main-Class=" + mainClass);
        JarEntry entry = jar.getJarEntry("app/Greeting.class");
        System.out.println("JarFile getJarEntry app/Greeting.class size=" + entry.getSize()
                + " crc=" + Long.toHexString(entry.getCrc()));
        Enumeration<JarEntry> all = jar.entries();
        int counted = 0;
        while (all.hasMoreElements())
        {
            JarEntry e = all.nextElement();
            counted += 1;
        }
        System.out.println("JarFile enumerated=" + counted);

        // --- classes out of the jar, through the stock API ---
        // The classic archive class loader: find the entry, read its bytes, defineClass, then drive the
        // result reflectively. Nothing here is joe-ng-specific -- it is what a hosted JVM does.
        JarClassLoader loader = new JarClassLoader(jar);
        Class<?> greeting = loader.loadFromJar("app.Greeting");
        System.out.println("loaded " + greeting.getName() + " from the jar");
        Object g = greeting.getDeclaredConstructor(String.class).newInstance("jar");
        Method text = greeting.getDeclaredMethod("text");
        // consonants() dispatches text() VIRTUALLY on itself -- the call that used to hit the null-vtable
        // guard, because RTA pruned every method of a defineClass'd class as unreachable and fillTib then
        // filled a vtable of zeros. It is the regression test for the root-blob fix.
        Method consonants = greeting.getDeclaredMethod("consonants");
        System.out.println("Greeting.text() = " + text.invoke(g)
                + " (" + consonants.invoke(g) + " consonants)");

        // A SECOND defineClass batch, referring back to the first: app.Main does `new Greeting(...)` and
        // calls it virtually, across the batch boundary. Its main is then invoked reflectively.
        Class<?> main = loader.loadFromJar(mainClass);
        Method entryPoint = main.getDeclaredMethod("main", String[].class);
        System.out.println("--- running " + mainClass + " (2nd defineClass batch) ---");
        entryPoint.invoke(null, (Object) new String[] { "reflectively" });

        // Running the jar's OWN main is the other route: /etc/init's `classpath=<jar>` puts the archive on the
        // VM's class path, and `main=app/Main` then launches straight out of it, closure and all.
        System.out.println("Main-Class " + mainClass + " runs via /etc/init classpath=");

        jar.close();
    }
}

/**
 * The classic archive class loader: find the {@code .class} entry, read its bytes, define the class. Nothing
 * here is joe-ng-specific — it is the same code that would run on a hosted JVM.
 */
class JarClassLoader extends ClassLoader
{
    private final JarFile jar;

    JarClassLoader(JarFile jar)
    {
        this.jar = jar;
    }

    /** Load a binary (dotted) class name out of the jar. */
    Class<?> loadFromJar(String binaryName) throws Exception
    {
        JarEntry e = jar.getJarEntry(binaryName.replace('.', '/') + ".class");
        if (e == null)
        {
            throw new ClassNotFoundException(binaryName);
        }
        InputStream in = jar.getInputStream(e);
        byte[] bytes = readAll(in);
        in.close();
        return defineClass(binaryName, bytes, 0, bytes.length);
    }

    private static byte[] readAll(InputStream in) throws Exception
    {
        byte[] out = new byte[256];
        int have = 0;
        int n = in.read(out, have, out.length - have);
        while (n > 0)
        {
            have += n;
            if (have == out.length)
            {
                byte[] bigger = new byte[out.length * 2];
                System.arraycopy(out, 0, bigger, 0, have);
                out = bigger;
            }
            n = in.read(out, have, out.length - have);
        }
        byte[] exact = new byte[have];
        System.arraycopy(out, 0, exact, 0, have);
        return exact;
    }
}
