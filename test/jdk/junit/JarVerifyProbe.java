/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-01
 */

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.CodeSigner;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Signed-jar verification, stock: JarFile and JarInputStream over java.util.jar.JarVerifier and
 * sun.security.util.SignatureFileVerifier. joe-ng denied JarVerifier until the JCA ran, so an unsigned jar read fine
 * and a signed one halted at its first verifying call. The jar is a real one -- keytool made an EC (secp256r1) key,
 * jarsigner signed a two-entry jar with it -- embedded as Base64 because a probe's own files are not resources here.
 * From it the probe builds two more at run time with ZipOutputStream, so their CRCs stay valid and only the
 * SIGNATURE can object: a TAMPERED copy (a.txt's bytes changed, the signature files kept) and an EXTENDED one (an
 * entry the manifest never mentions, which must read as unsigned). Each arm runs on its own and prints what it got.
 */
public class JarVerifyProbe
{
    interface Arm
    {
        String run() throws Throwable;
    }

    static void report(String what, Arm arm)
    {
        String got;
        try
        {
            got = arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName() + " " + t.getMessage();
        }
        System.out.println(what + ": " + got);
    }

    /** The signed jar: META-INF/MANIFEST.MF (X-Probe: signed), PROBE.SF, PROBE.EC, a.txt, b.txt. */
    static final String SIGNED_JAR =
              "UEsDBBQACAgIAGCUYV0AAAAAAAAAAAAAAAAUAAAATUVUQS1JTkYvTUFOSUZFU1QuTUZtzc1ugjAAAOB7k75Dj1sMfxMp4WZhm8gg"
            + "qIOptxJKV6dFKKa4px877bL7l3wplaJhajBK1ivRygA5pg1BSoU0wjNVKkBZO3wKySHYG3nfVixASnDJagjCntGB1Qa5B+jJM23T"
            + "QQ+r9sKqnulHCCDI6GXi1BzGAYLdamnMfdeIBJ/CAGlsfV9tbMniePPFqBfWrOvdJIqXvGIEx2IbeeVpPIZN6m1yx/vqo0MzDmsI"
            + "UKT1oitm57+j+v84Yb71nmU7FmF9UOlr1ogP4uf7XUiuSfKOGe40mfMx23TqxV2/xZj4QjBrOnRJ2lsc89/jB1BLBwir2CXT7wAA"
            + "ACYBAABQSwMEFAAICAgAYJRhXQAAAAAAAAAAAAAAABEAAABNRVRBLUlORi9QUk9CRS5TRnXPy26CQACF4T0J78CyzQS5FFHZgVVU"
            + "Lt7qZdjNwICjgsiMRXj6km6aNO3urP4vZ0uzAvFHReQ9qRi9FZak9VRRGFcEcZLITmNJutlTe5r0MrvlBFekfhWF7cyW34aG/E4z"
            + "wrgcoIKm3bCkSQDAGK21YOU7yGejxokX7+wZkAukeo3tyPdCPCqZl7FbpF9EQYpOm3v/eg+9VXXSSq1//bfeDVrINucVxQ9OmCWh"
            + "M166d392rQHuK8tpzPdJAup2YLbp2lx09RIP4GHdqspkpENXL8vEjJ+xc6bthgCeYEUURCFEOeliPf7kv3FLgkxFXsVtg0Supgfc"
            + "C7B6jcIc5rzYqfrc9qfUiO45umFlvD+ycrIA2WA+6fBUay/NvBz+GPhv4+inx1MyVYERtVx35vDZLDSMG5C5+TJ2FYAhZXlWg3BQ"
            + "NnCcO6Ozmh6Q0Rk79xOCD//7xxdQSwcI8CMQIFcBAADNAQAAUEsDBBQACAgIAGCUYV0AAAAAAAAAAAAAAAARAAAATUVUQS1JTkYv"
            + "UFJPQkUuRUMzaGI6x8ap1ebR9p2XkZ1pQRPTfoMmpt1MjIyG/Aa8bJwJbR6MqcwsTEysDAbcCIWMC5oYQwyaGAMMGr8tYGZiZGLi"
            + "ZLjPMLP//GL3HAMuNg6gunO2LMzMBtKGkgbibMyhLMzCAln5qbp56QpZiUUKBUX5SakGCuK8RmaGhgaGRsbGJqaWURL8RoZAAQMD"
            + "C6gAfu2RBsJs7GCbmBihVjIzsjM7MbA8bT048bSthVXtd55fEd7zfDYHfVgZvmjyiXuBZxc3X5zK0Xnik/jVxBSmzaHXpvYnL3qa"
            + "V9H7a97j3+/lBX86yu6qubtY0UDeQBZorywfixiLiE/+rt7z9qzqDgJLWJ6scmr7Zrb6PLIvmT0ZDNyYFBm6HFm094ZW+35r5bJh"
            + "ustywtY1Ycb+F2+mx817eNBcQuYdUMnRhXMkP0a+aHh51MNqk2S5SqnbCoUT88VCvtmFythIt18zbGK0A4arFTACDDTweh85xFEj"
            + "akHjUgMJeFxxMhsiR5yBDEKG1ZAfKQZMDQyiDLQQsiaGsgbSaEYvRI5be4RaFkMjFoMd5o+ldBJtrDrCyxMMlunslD2r9piDdfsr"
            + "2d2PNqz7eJyJuap4a7rVGwHbA0lMqlFpK5CDkMUDHIKLTeq8jGb9kVpg7ng/6fbbq6rxdmbZ+xYceN2Q8LbQM2ADUMm3N9serd2b"
            + "XcUcc+ZDfUqExtfOvNmX3Fo5PUyi7cxuq3EBAFBLBwjb8SzbIQIAANICAABQSwMECgAACAAAXZRhXQAAAAAAAAAAAAAAAAkABABN"
            + "RVRBLUlORi/+ygAAUEsDBBQACAgIAF2UYV0AAAAAAAAAAAAAAAAFAAAAYS50eHTLSM3JyVdIK8rPVUhUKM5Mz0tNUchKLOICAFBL"
            + "BwgCbHU5GgAAABgAAABQSwMEFAAICAgAXZRhXQAAAAAAAAAAAAAAAAUAAABiLnR4dCtOTc7PS1FIzSspquQCAFBLBwhu2OeNDwAA"
            + "AA0AAABQSwECFAAUAAgICABglGFdq9gl0+8AAAAmAQAAFAAAAAAAAAAAAAAAAAAAAAAATUVUQS1JTkYvTUFOSUZFU1QuTUZQSwEC"
            + "FAAUAAgICABglGFd8CMQIFcBAADNAQAAEQAAAAAAAAAAAAAAAAAxAQAATUVUQS1JTkYvUFJPQkUuU0ZQSwECFAAUAAgICABglGFd"
            + "2/Es2yECAADSAgAAEQAAAAAAAAAAAAAAAADHAgAATUVUQS1JTkYvUFJPQkUuRUNQSwECCgAKAAAIAABdlGFdAAAAAAAAAAAAAAAA"
            + "CQAEAAAAAAAAAAAAAAAnBQAATUVUQS1JTkYv/soAAFBLAQIUABQACAgIAF2UYV0CbHU5GgAAABgAAAAFAAAAAAAAAAAAAAAAAFIF"
            + "AABhLnR4dFBLAQIUABQACAgIAF2UYV1u2OeNDwAAAA0AAAAFAAAAAAAAAAAAAAAAAJ8FAABiLnR4dFBLBQYAAAAABgAGAGEBAADh"
            + "BQAAAAA=";

    static File signed;
    static File tampered;
    static File extended;

    static byte[] readAll(InputStream in) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) > 0)
        {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Copy {@code from} to {@code to}, replacing {@code name}'s bytes (null keeps them), then adding {@code add}. */
    static void copy(File from, File to, String name, byte[] bytes, String add) throws Exception
    {
        try (ZipFile z = new ZipFile(from); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(to)))
        {
            for (ZipEntry e : Collections.list(z.entries()))
            {
                out.putNextEntry(new ZipEntry(e.getName()));
                byte[] b = e.getName().equals(name) ? bytes : readAll(z.getInputStream(e));
                out.write(b);
                out.closeEntry();
            }
            if (add != null)
            {
                out.putNextEntry(new ZipEntry(add));
                out.write("not in the manifest\n".getBytes());
                out.closeEntry();
            }
        }
    }

    /** Who signed {@code e}, after its bytes were read: "unsigned" or the signer certificate's subject. */
    static String signers(CodeSigner[] cs)
    {
        if (cs == null)
        {
            return "unsigned";
        }
        List<String> names = new ArrayList<>();
        for (CodeSigner s : cs)
        {
            X509Certificate c = (X509Certificate) s.getSignerCertPath().getCertificates().get(0);
            names.add(c.getSubjectX500Principal().getName());
        }
        return names.toString();
    }

    /** Read every entry of {@code f} (verifying), then report each one's signers. */
    static String readAndSign(File f) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        try (JarFile jf = new JarFile(f))
        {
            List<JarEntry> entries = Collections.list(jf.entries());
            for (JarEntry e : entries)
            {
                if (!e.isDirectory() && !e.getName().startsWith("META-INF/"))
                {
                    try (InputStream in = jf.getInputStream(e))
                    {
                        readAll(in);
                    }
                    sb.append(e.getName()).append('=').append(signers(e.getCodeSigners())).append(' ');
                }
            }
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) throws Exception
    {
        File dir = new File(System.getProperty("java.io.tmpdir"));
        signed = new File(dir, "jarverify-signed.jar");
        tampered = new File(dir, "jarverify-tampered.jar");
        extended = new File(dir, "jarverify-extended.jar");
        try (FileOutputStream out = new FileOutputStream(signed))
        {
            out.write(Base64.getDecoder().decode(SIGNED_JAR));
        }
        copy(signed, tampered, "a.txt", "hello from a FORGED jar\n".getBytes(), null);
        copy(signed, extended, null, null, "c.txt");

        report("manifest attribute", () ->
        {
            try (JarFile jf = new JarFile(signed))
            {
                return jf.getManifest().getMainAttributes().getValue("X-Probe");
            }
        });
        report("entries", () ->
        {
            try (JarFile jf = new JarFile(signed))
            {
                List<String> names = new ArrayList<>();
                for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); )
                {
                    names.add(en.nextElement().getName());
                }
                return names.toString();
            }
        });
        report("a.txt content", () ->
        {
            try (JarFile jf = new JarFile(signed); InputStream in = jf.getInputStream(jf.getEntry("a.txt")))
            {
                return new String(readAll(in)).trim();
            }
        });
        report("signers after reading", () -> readAndSign(signed));
        report("signers before reading", () ->
        {
            try (JarFile jf = new JarFile(signed))
            {
                return signers(jf.getJarEntry("a.txt").getCodeSigners());
            }
        });
        report("JarInputStream verifying", () ->
        {
            StringBuilder sb = new StringBuilder();
            try (JarInputStream in = new JarInputStream(new FileInputStream(signed), true))
            {
                JarEntry e;
                while ((e = in.getNextJarEntry()) != null)
                {
                    readAll(in);
                    sb.append(e.getName()).append('=').append(signers(e.getCodeSigners())).append(' ');
                }
                sb.append("X-Probe=").append(in.getManifest().getMainAttributes().getValue("X-Probe"));
            }
            return sb.toString();
        });
        report("tampered entry", () -> readAndSign(tampered));
        report("tampered, verify=false", () ->
        {
            try (JarFile jf = new JarFile(tampered, false); InputStream in = jf.getInputStream(jf.getEntry("a.txt")))
            {
                return new String(readAll(in)).trim();
            }
        });
        report("tampered, untouched entry", () ->
        {
            try (JarFile jf = new JarFile(tampered); InputStream in = jf.getInputStream(jf.getEntry("b.txt")))
            {
                return new String(readAll(in)).trim();
            }
        });
        report("extended jar", () -> readAndSign(extended));
        report("tampered via JarInputStream", () ->
        {
            try (JarInputStream in = new JarInputStream(new FileInputStream(tampered), true))
            {
                JarEntry e;
                while ((e = in.getNextJarEntry()) != null)
                {
                    readAll(in);
                }
            }
            return "read through";
        });
        System.out.println("JarVerifyProbe done");
    }
}
