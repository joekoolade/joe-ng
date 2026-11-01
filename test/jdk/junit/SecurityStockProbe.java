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

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;

/**
 * The security arc's measuring stick: the STOCK JCA surface of java.base, one arm per service. Every arm is
 * deterministic so the host JVM's output is the expectation -- random keys print only what does not depend on the
 * randomness (algorithm, a sign/verify round trip). Each arm runs on its own and prints what it got, so a failing
 * service names itself rather than hiding the ones after it.
 */
public class SecurityStockProbe
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
            got = "threw " + t.getClass().getName();
        }
        System.out.println(what + ": " + got);
    }

    /** A fixed self-signed secp256r1 certificate (keytool, CN=joe-ng probe), so parsing is deterministic. */
    static final String CERT =
            "MIIBaTCCARCgAwIBAgIJAKwz/1BikivBMAoGCCqGSM49BAMDMCgxDzANBgNVBAoTBmpvZS1uZzEVMBMGA1UEAxMMam9lLW5n"
            + "IHByb2JlMCAXDTI2MDEwMTIxMjYzMloYDzIxMjUxMjA4MjEyNjMyWjAoMQ8wDQYDVQQKEwZqb2UtbmcxFTATBgNVBAMTDGpv"
            + "ZS1uZyBwcm9iZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAvD1Rv+/AzIN6bFyKdQinsOo1sOlSEAZj84M3EztdegMuNv"
            + "Pc+cAV2M5m0gFBzvQJwQDxCGCM/gJbmArNwJRr2jITAfMB0GA1UdDgQWBBT7P/fDUe7+mi5ADTPo0lCe56rcmjAKBggqhkjO"
            + "PQQDAwNHADBEAiBup4TgpiUGp8zz3peXccVDUjs/Ndl0HbmVMcaLSqL/kwIgHE7tGseSWqJIutqBcepuiX3xEh8xu5nsN3q/"
            + "WxaR3rE=";

    static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);

    static String hex(byte[] b)
    {
        return HexFormat.of().formatHex(b);
    }

    public static void main(String[] args)
    {
        report("SUN provider", () -> Security.getProvider("SUN") != null ? Security.getProvider("SUN").getName()
                : "absent");
        report("SHA-256 provider", () -> MessageDigest.getInstance("SHA-256").getProvider().getName());
        report("SHA-256(abc)", () -> hex(MessageDigest.getInstance("SHA-256").digest(ABC)));
        report("SHA3-256(abc)", () -> hex(MessageDigest.getInstance("SHA3-256").digest(ABC)));
        report("HmacSHA256", () ->
        {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec("key".getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return hex(m.doFinal(ABC));
        });
        report("SHA1PRNG seeded", () ->
        {
            SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(42L);
            return Integer.toString(r.nextInt());
        });
        report("AES/GCM round trip", () ->
        {
            SecretKey k = new SecretKeySpec(new byte[16], "AES");
            GCMParameterSpec iv = new GCMParameterSpec(128, new byte[12]);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, iv);
            byte[] ct = c.doFinal(ABC);
            c.init(Cipher.DECRYPT_MODE, k, iv);
            return hex(ct) + " -> " + new String(c.doFinal(ct), StandardCharsets.US_ASCII);
        });
        report("AES keygen", () ->
        {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return g.generateKey().getEncoded().length + " bytes";
        });
        report("X.509 parse", () ->
        {
            CertificateFactory f = CertificateFactory.getInstance("X.509");
            X509Certificate x = (X509Certificate) f.generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(CERT)));
            return x.getSubjectX500Principal().getName() + " " + x.getSigAlgName() + " serial="
                    + x.getSerialNumber().toString(16);
        });
        report("X.509 self-verify", () ->
        {
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(CERT)));
            x.verify(x.getPublicKey());
            return "verified";
        });
        report("EC key from cert", () ->
        {
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(CERT)));
            byte[] enc = x.getPublicKey().getEncoded();
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(enc)).getAlgorithm() + " "
                    + enc.length + " bytes";
        });
        report("RSA sign/verify", () ->
        {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(1024);
            KeyPair kp = g.generateKeyPair();
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(kp.getPrivate());
            s.update(ABC);
            byte[] sig = s.sign();
            s.initVerify(kp.getPublic());
            s.update(ABC);
            return "verify=" + s.verify(sig) + " bits=" + ((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .getModulus().bitLength();
        });
        report("EC sign/verify", () ->
        {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            KeyPair kp = g.generateKeyPair();
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(kp.getPrivate());
            s.update(ABC);
            byte[] sig = s.sign();
            s.initVerify(kp.getPublic());
            s.update(ABC);
            return "verify=" + s.verify(sig);
        });
        report("PKCS12 keystore", () ->
        {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            return "empty size=" + ks.size();
        });
        report("SSLContext", () -> SSLContext.getDefault().getProtocol());
        report("BigInteger.modPow", () -> new BigInteger("65537").modPow(new BigInteger("12345"),
                new BigInteger("1000000007")).toString());
        System.out.println("SecurityStockProbe done");
    }
}
