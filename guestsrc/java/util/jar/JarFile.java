package java.util.jar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A JDK-free {@code java.util.jar.JarFile} overlay (wins by name). It is a thin layer over the
 * {@link ZipFile} overlay — exactly as the stock class is a thin layer over the stock {@code ZipFile} — adding
 * the two things that make a zip a jar: entries are {@link JarEntry} objects, and {@link #getManifest} parses
 * {@code META-INF/MANIFEST.MF}. Both {@code JarEntry} and {@code Manifest}/{@code Attributes} are the
 * UNMODIFIED stock classes; only the file-access floor beneath them is replaced.
 *
 * <p>What the stock class does and this does not: signature verification ({@code JarVerifier} and the whole
 * {@code sun.security} closure), multi-release versioned entry selection, and {@code Class-Path} manifest
 * expansion. A signed jar reads here as an ordinary jar — its signature files are just entries.
 */
public class JarFile extends ZipFile
{
    public static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    private Manifest manifest;
    private boolean manifestRead;

    public JarFile(String name) throws IOException
    {
        super(name);
    }

    public JarFile(String name, boolean verify) throws IOException
    {
        super(name);
    }

    /** The parsed {@code META-INF/MANIFEST.MF}, or null when the jar has none. Parsed once, then cached. */
    public Manifest getManifest() throws IOException
    {
        if (!manifestRead)
        {
            manifestRead = true;
            ZipEntry e = getEntry(MANIFEST_NAME);
            if (e != null)
            {
                InputStream in = getInputStream(e);
                if (in != null)
                {
                    manifest = new Manifest(in);       // stock Manifest, stock Attributes
                    in.close();
                }
            }
        }
        return manifest;
    }

    /** The named entry as a {@link JarEntry}, or null. */
    public JarEntry getJarEntry(String name)
    {
        ZipEntry e = getEntry(name);
        return e == null ? null : new JarEntry(e);     // stock JarEntry(ZipEntry) copies every field
    }

    @Override
    public Enumeration<JarEntry> entries()
    {
        return new JarEntries(super.entries());
    }

    /** Always false: versioned ({@code META-INF/versions/}) entry selection is not implemented. */
    public boolean isMultiRelease()
    {
        return false;
    }

    /** Wraps the {@link ZipFile} cursor so every entry comes out as a {@link JarEntry}. */
    private static final class JarEntries implements Enumeration<JarEntry>
    {
        private final Enumeration<? extends ZipEntry> zip;

        JarEntries(Enumeration<? extends ZipEntry> zip)
        {
            this.zip = zip;
        }

        @Override
        public boolean hasMoreElements()
        {
            return zip.hasMoreElements();
        }

        @Override
        public JarEntry nextElement()
        {
            return new JarEntry(zip.nextElement());
        }
    }
}
