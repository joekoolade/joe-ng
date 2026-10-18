/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The stock {@code java.util.zip.ZipFile} and {@code java.util.jar.JarFile} over the VM's writable tree: an archive
 * written with ZipOutputStream (a STORED entry, a DEFLATED one, a directory, a comment) read back by File and by
 * name -- size, comment, entries in order with method/size/crc, an entry by name and a directory by either form, an
 * absent one, each entry's bytes, {@code stream()}, a second ZipFile over the same file outliving the first, the
 * entry iterator after close and the calls a closed ZipFile refuses. Then a jar: a manifest written by
 * JarOutputStream and read by JarFile, its entries as JarEntry, and the jar the image ships. Then the refusals: a
 * file that is not a zip, a missing file, a directory. Paths print relative to the root, exceptions by class and
 * message with the root removed. The argument is the tree's root: empty on the metal, on a host a fresh writable
 * copy of {@code ramfs/} with an empty {@code tmp/} (see FileProbe).
 */
public class ZipFileProbe
{
    static String root = "";

    static String f(String p)
    {
        return root + p;
    }

    interface Arm
    {
        Object call() throws Exception;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            System.out.println(what + " -> " + a.call());
        }
        catch (Exception e)
        {
            String m = e.getMessage();
            System.out.println(what + " -> " + e.getClass().getName()
                    + (m == null ? "" : ": " + m.replace(root, "")));
        }
    }

    static final String STORED = "stored text, kept as it is\n";
    static final String DEFLATED = "deflated text deflated text deflated text deflated text\n";

    static void writeZip(String path) throws IOException
    {
        try (OutputStream out = new FileOutputStream(f(path));
             ZipOutputStream zo = new ZipOutputStream(out))
        {
            byte[] s = STORED.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(s);
            ZipEntry e = new ZipEntry("a/stored.txt");
            e.setMethod(ZipEntry.STORED);
            e.setSize(s.length);
            e.setCompressedSize(s.length);
            e.setCrc(crc.getValue());
            zo.putNextEntry(e);
            zo.write(s);
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("a/"));
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("deflated.txt"));
            zo.write(DEFLATED.getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            zo.setComment("the probe's archive");
        }
    }

    static String text(ZipFile z, ZipEntry e) throws IOException
    {
        try (InputStream in = z.getInputStream(e))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String describe(ZipEntry e)
    {
        // A DEFLATED entry's compressed size is the compressor's business, not ZipFile's: this VM's Deflater
        // writes stored blocks (zip.Deflate) and zlib does not, so only a STORED entry's is printed.
        String csize = e.getMethod() == ZipEntry.STORED ? "/" + e.getCompressedSize() : "";
        return e.getName() + "/" + e.getMethod() + "/" + e.getSize() + csize + "/"
                + Long.toHexString(e.getCrc()) + (e.isDirectory() ? "/dir" : "");
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            root = args[0];
        }
        arm("write an archive", () ->
        {
            writeZip("/tmp/p.zip");
            return new File(f("/tmp/p.zip")).length() > 0;
        });
        arm("open by File", () ->
        {
            try (ZipFile z = new ZipFile(new File(f("/tmp/p.zip"))))
            {
                return z.size() + " " + z.getName().replace(root, "") + " comment=" + z.getComment();
            }
        });
        arm("entries in order", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                List<String> out = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements())
                {
                    out.add(describe(en.nextElement()));
                }
                return out;
            }
        });
        arm("an entry by name", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                return describe(z.getEntry("deflated.txt")) + " absent=" + z.getEntry("nope.txt");
            }
        });
        arm("a directory by either form", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                return z.getEntry("a").getName() + " " + z.getEntry("a/").getName();
            }
        });
        arm("read each entry", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                return text(z, z.getEntry("a/stored.txt")).equals(STORED) + " "
                        + text(z, z.getEntry("deflated.txt")).equals(DEFLATED) + " dir=["
                        + text(z, z.getEntry("a/")) + "]";
            }
        });
        arm("available and skip", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip"));
                 InputStream in = z.getInputStream(z.getEntry("a/stored.txt")))
            {
                int before = in.available();
                long s = in.skip(7);
                int b = in.read();
                return before + " " + s + " " + (char) b + " " + in.available();
            }
        });
        arm("a deflated entry a byte at a time", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip"));
                 InputStream in = z.getInputStream(z.getEntry("deflated.txt")))
            {
                StringBuilder sb = new StringBuilder();
                int b = in.read();
                while (b >= 0)
                {
                    sb.append((char) b);
                    b = in.read();
                }
                return sb.toString().equals(DEFLATED) + " then " + in.read();
            }
        });
        for (String name : new String[] { "a/stored.txt", "deflated.txt" })
        {
            arm("read one, close the ZipFile, read: " + name, () ->
            {
                InputStream in;
                int first;
                try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
                {
                    in = z.getInputStream(new ZipEntry(name));
                    first = in.read();
                }
                return (char) first + " " + in.read();
            });
        }
        arm("stream of names", () ->
        {
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                List<String> out = new ArrayList<>();
                Iterator<? extends ZipEntry> it = z.stream().iterator();
                while (it.hasNext())
                {
                    out.add(it.next().getName());
                }
                return out;
            }
        });
        arm("a second ZipFile outlives the first", () ->
        {
            ZipFile one = new ZipFile(f("/tmp/p.zip"));
            ZipFile two = new ZipFile(f("/tmp/p.zip"));
            one.close();
            String t = text(two, two.getEntry("deflated.txt"));
            two.close();
            return t.equals(DEFLATED);
        });
        arm("the iterator after close", () ->
        {
            Enumeration<? extends ZipEntry> en;
            try (ZipFile z = new ZipFile(f("/tmp/p.zip")))
            {
                en = z.entries();
            }
            return en.hasMoreElements();
        });
        arm("getEntry after close", () ->
        {
            ZipFile z = new ZipFile(f("/tmp/p.zip"));
            z.close();
            return z.getEntry("deflated.txt");
        });
        arm("a stream after its ZipFile closed", () ->
        {
            ZipFile z = new ZipFile(f("/tmp/p.zip"));
            InputStream in = z.getInputStream(z.getEntry("deflated.txt"));
            z.close();
            return in.read();
        });
        arm("write a jar", () ->
        {
            Manifest m = new Manifest();
            m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "probe.Main");
            try (JarOutputStream jo = new JarOutputStream(new FileOutputStream(f("/tmp/p.jar")), m))
            {
                jo.putNextEntry(new JarEntry("probe/Main.txt"));
                jo.write("main".getBytes(StandardCharsets.UTF_8));
                jo.closeEntry();
            }
            return new File(f("/tmp/p.jar")).length() > 0;
        });
        arm("read the jar", () ->
        {
            try (JarFile j = new JarFile(f("/tmp/p.jar")))
            {
                Manifest m = j.getManifest();
                JarEntry e = j.getJarEntry("probe/Main.txt");
                List<String> names = new ArrayList<>();
                Enumeration<JarEntry> en = j.entries();
                while (en.hasMoreElements())
                {
                    JarEntry x = en.nextElement();
                    names.add(x.getName() + (x.getClass() == JarEntry.class ? "" : ":" + x.getClass().getSimpleName()));
                }
                return m.getMainAttributes().getValue("Main-Class") + " " + text(j, e) + " " + names
                        + " mr=" + j.isMultiRelease();
            }
        });
        arm("JarEntry instanceof, from entries() and stream()", () ->
        {
            try (JarFile j = new JarFile(f("/tmp/p.jar")))
            {
                ZipEntry first = j.entries().nextElement();
                StringBuilder sb = new StringBuilder();
                sb.append("entries=").append(first instanceof JarEntry);
                Iterator<JarEntry> it = j.stream().iterator();
                sb.append(" stream=").append(it.next() instanceof JarEntry);
                j.stream().forEach(e -> sb.append(" lambda=").append(e instanceof JarEntry));
                Object o = j.getJarEntry("probe/Main.txt");
                sb.append(" object=").append(o instanceof JarEntry).append(" cast=")
                        .append(((JarEntry) o).getName());
                return sb.toString();
            }
        });
        arm("the image's jar", () ->
        {
            try (JarFile j = new JarFile(new File(f("/lib/app.jar"))))
            {
                List<String> names = new ArrayList<>();
                Enumeration<JarEntry> en = j.entries();
                while (en.hasMoreElements())
                {
                    names.add(en.nextElement().getName());
                }
                return j.size() + " " + names + " main=" + j.getManifest().getMainAttributes().getValue("Main-Class");
            }
        });
        arm("not a zip", () -> new ZipFile(f("/etc/motd")));
        arm("a missing file", () -> new ZipFile(f("/tmp/missing.zip")));
        arm("a directory", () -> new ZipFile(f("/data")));
        arm("cleanup", () ->
        {
            Files.delete(Path.of(f("/tmp/p.zip")));
            Files.delete(Path.of(f("/tmp/p.jar")));
            String[] left = new File(f("/tmp")).list();
            Arrays.sort(left);
            return Arrays.toString(left);
        });
        System.out.println("ZipFileProbe done");
    }
}
