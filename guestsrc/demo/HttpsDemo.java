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
package demo;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;

/**
 * An HTTPS GET with nothing but stock java.base: HttpsURLConnection over sun.security.ssl, the server's chain
 * validated against java.home/lib/security/cacerts, the session keyed from the board's hardware RNG (NativePRNG
 * over /dev/random), the certificate dates checked against the SNTP-set clock, and the bytes carried by
 * java.net.Socket over the VM's own WiFi/TCP stack. QEMU has no network, so this runs on the Pi from /etc/init
 * (net=1); SslEngineProbe is the same TLS stack where QEMU can reach it.
 */
public class HttpsDemo
{
    private static final int MAX_ATTEMPTS = 5;

    public static void main(String[] args) throws Exception
    {
        String host = args.length > 0 ? args[0] : "example.com";
        long t0 = System.nanoTime();
        // The connection resolves the host inside InetSocketAddress, which swallows the UnknownHostException
        // and leaves only "unresolved" -- so resolve it here first, where the failure and its timing print.
        try
        {
            InetAddress a = InetAddress.getByName(host);
            System.out.println("https: dns " + host + " = " + a.getHostAddress() + " in "
                    + (System.nanoTime() - t0) / 1000000L + " ms");
        }
        catch (UnknownHostException e)
        {
            System.out.println("https: dns " + host + " FAILED in " + (System.nanoTime() - t0) / 1000000L
                    + " ms: " + e.getMessage());
        }
        // Retry: a failed handshake still runs our side of it to the end, so the next attempt finds the paths for
        // THIS server's chain compiled. The server closes ~15 s after the SYN, and on the Pi the first-use load
        // and compile of the handshake path takes minutes, so the early attempts fail with a broken pipe. Pi,
        // 2026-11-03: attempts 1 and 2 failed (468 s, 527 s) and attempt 3 connected in 2 s. An in-memory warm-up
        // handshake before the first attempt was measured against this and LOST: it took 485 s, attempt 1 still
        // failed, and the total was 1327 s vs 1029 s without it.
        HttpsURLConnection c = null;
        int attempt = 1;
        while (c == null)
        {
            long ta = System.nanoTime();
            HttpsURLConnection k = (HttpsURLConnection) new URI("https://" + host + "/").toURL().openConnection();
            k.setConnectTimeout(30000);
            k.setReadTimeout(30000);
            try
            {
                k.connect();
                c = k;
                System.out.println("https: attempt " + attempt + " connected in " + (System.nanoTime() - ta) / 1000000L
                        + " ms");
            }
            catch (java.io.IOException e)
            {
                System.out.println("https: attempt " + attempt + " failed in " + (System.nanoTime() - ta) / 1000000L
                        + " ms: " + e);
                if (attempt == MAX_ATTEMPTS)
                {
                    throw e;
                }
                attempt = attempt + 1;
            }
        }
        long t1 = System.nanoTime();
        System.out.println("https: connected in " + (t1 - t0) / 1000000L + " ms, cipher " + c.getCipherSuite());
        Certificate[] chain = c.getServerCertificates();
        for (int i = 0; i < chain.length; i++)
        {
            X509Certificate x = (X509Certificate) chain[i];
            System.out.println("https: cert " + i + " " + x.getSubjectX500Principal().getName() + " (issuer "
                    + x.getIssuerX500Principal().getName() + ")");
        }
        int code = c.getResponseCode();
        System.out.println("https: " + code + " " + c.getResponseMessage() + ", " + c.getContentType());
        InputStream in = c.getInputStream();
        byte[] buf = new byte[2048];
        int total = 0;
        StringBuilder head = new StringBuilder();
        int n = in.read(buf);
        while (n > 0)
        {
            if (head.length() < 120)
            {
                head.append(new String(buf, 0, Math.min(n, 120 - head.length())));
            }
            total += n;
            n = in.read(buf);
        }
        in.close();
        c.disconnect();
        System.out.println("https: body starts " + head);
        System.out.println("https: done, " + total + " bytes in " + (System.nanoTime() - t0) / 1000000L + " ms");
    }
}
