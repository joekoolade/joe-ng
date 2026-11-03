/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-02
 */
package demo;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * Run whole TLS handshakes in memory, before any socket exists, so the client side of sun.security.ssl is loaded
 * and compiled before a real server is waiting on it. Pi run 19: the server's whole flight arrived 4 ms after
 * our ClientHello, but our ChangeCipherSpec left 9.4 s later and our Finished ~38 s after that; the server had
 * closed at 11.7 s. Two sessions against a local server (the SslEngineProbe key: EC secp256r1, CN=localhost):
 * one with a client that trusts that certificate, which runs the handshake to the end (key exchange, ECDSA
 * CertificateVerify, Finished, record encryption), and one with the DEFAULT trust store and HTTPS endpoint
 * checking, which runs the real PKIX path against cacerts and is expected to reject the self-signed server.
 */
public class TlsWarmup
{
    /** PKCS12, store and key password "serverpass", alias "server" (the SslEngineProbe server key). */
    static final String SERVER_P12 =
              "MIIEAAIBAzCCA6oGCSqGSIb3DQEHAaCCA5sEggOXMIIDkzCCASoGCSqGSIb3DQEHAaCCARsEggEXMIIBEzCCAQ8GCyqGSIb3DQEM"
            + "CgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBS37EAPGIjekTJjgVkEVxL9f6SHpgICJxACASAwDAYIKoZI"
            + "hvcNAgkFADAdBglghkgBZQMEASoEEBbN6neMgA+WuMtyctTnld0EUKQHbcJwkv3nszYi5UzFRyTlzb9kQrvOqa7acAEasSaZaBy/"
            + "uWEtFnfziXOJ58hcghlMK8bPyKiBmBY6n71kT5Gd453vLvm9vHgSttHKeShCMUAwGwYJKoZIhvcNAQkUMQ4eDABzAGUAcgB2AGUA"
            + "cjAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkzNTc4NTYwNDUyMIICYQYJKoZIhvcNAQcGoIICUjCCAk4CAQAwggJHBgkqhkiG9w0B"
            + "BwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFIR4OWigwULGuSpbSq4YUbu5vZTMAgInEAIBIDAMBggqhkiG9w0CCQUA"
            + "MB0GCWCGSAFlAwQBKgQQ2jFUJNRUPbwbNCJhlr4+84CCAdDPVnMNm4AhUIPrpqvPFfsF/HuRJgSYu8x6kpjQ+jK/ga3466dzDR/Q"
            + "QezPyersIpqok8CLihzefZ2jJ4luT9Ycj+qUKyzmQmmRMrUiZQfAbg7xRgWk18g+YdTMx0igCzMdqXSjfDiI5JVGRAEYLBWXwf2i"
            + "b6aIqL+kPmYq/rmRhlWx9UNs79oMcmsC3XgaH4as1bJcXUglT6AyvzSTD6egV7xEHRJn5SRHCy8K6aQdBJCTyXPxWeaV/KH3Gdqj"
            + "siSPoK6LirziRjiSe0JvaWgfsvKYKrqtav684BWyffNLMS466v1c5QdWbdmt91NHQY6QBPiOiAfyHNbFczWVbW9WHd3EHzclSUGn"
            + "iwym+/j4PLZQs5iYCv92cfq7HFdFQEMCxEFP43xeCb2pbO5zTnX6TJyNw1mgvsyjr4sXBUrtntQGMqaS8/Loz1Mp7S7f6k/Kjg2U"
            + "eew+TyN9WnBkXpqvHrXhgX63ZYnQMLLJHckn6MZmhxeD+n9YofoCb3oBm5OdTjEC0t7DdWr7q1+KQe43RNlM+X7bDTiPWF7mV8my"
            + "pPr/s6m0cYRpQ5AWEc85V19FCAJiMQn7oYRk/j00fZ8P/o61iGXPyn4xrR3wuIutyjBNMDEwDQYJYIZIAWUDBAIBBQAEIEjJQHx2"
            + "YVYSvLjie39ZNhjnbWjcwfNK/L5ZMfrLiMHuBBTVWinluqcV1tSYVxB0qvpvl+PNswICJxA=";

    public static void run()
    {
        long t = System.nanoTime();
        String pinned = attempt(true);
        long t1 = System.nanoTime();
        System.out.println("https: warm handshake (pinned) " + pinned + " in " + (t1 - t) / 1000000L + " ms");
        String dflt = attempt(false);
        System.out.println("https: warm handshake (default trust) " + dflt + " in "
                + (System.nanoTime() - t1) / 1000000L + " ms");
    }

    private static String attempt(boolean pinned)
    {
        try
        {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(SERVER_P12)), "serverpass".toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, "serverpass".toCharArray());
            SSLContext sctx = SSLContext.getInstance("TLS");
            sctx.init(kmf.getKeyManagers(), null, null);
            SSLContext cctx;
            if (pinned)
            {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                trust.setCertificateEntry("server", ks.getCertificate("server"));
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
                tmf.init(trust);
                cctx = SSLContext.getInstance("TLS");
                cctx.init(null, tmf.getTrustManagers(), null);
            }
            else
            {
                cctx = SSLContext.getDefault();
            }
            SSLEngine client = cctx.createSSLEngine("localhost", 443);
            client.setUseClientMode(true);
            SSLParameters p = client.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            client.setSSLParameters(p);
            SSLEngine server = sctx.createSSLEngine();
            server.setUseClientMode(false);
            return handshake(client, server);
        }
        catch (Throwable e)
        {
            return "threw " + e.getClass().getName();
        }
    }

    private static String handshake(SSLEngine client, SSLEngine server) throws Exception
    {
        int size = Math.max(client.getSession().getPacketBufferSize(), server.getSession().getPacketBufferSize());
        int app = Math.max(client.getSession().getApplicationBufferSize(),
                server.getSession().getApplicationBufferSize());
        ByteBuffer c2s = ByteBuffer.allocate(size);
        ByteBuffer s2c = ByteBuffer.allocate(size);
        ByteBuffer clientIn = ByteBuffer.allocate(app);
        ByteBuffer serverIn = ByteBuffer.allocate(app);
        ByteBuffer empty = ByteBuffer.allocate(0);
        client.beginHandshake();
        server.beginHandshake();
        int rounds = 0;
        while ((busy(client) || busy(server)) && rounds < 50)
        {
            rounds = rounds + 1;
            client.wrap(empty, c2s);
            runTasks(client);
            c2s.flip();
            server.unwrap(c2s, serverIn);
            runTasks(server);
            c2s.compact();
            server.wrap(empty, s2c);
            runTasks(server);
            s2c.flip();
            client.unwrap(s2c, clientIn);
            runTasks(client);
            s2c.compact();
        }
        if (busy(client) || busy(server))
        {
            return "unfinished";
        }
        return client.getSession().getProtocol() + " " + client.getSession().getCipherSuite();
    }

    private static void runTasks(SSLEngine e)
    {
        Runnable r = e.getDelegatedTask();
        while (r != null)
        {
            r.run();
            r = e.getDelegatedTask();
        }
    }

    private static boolean busy(SSLEngine e)
    {
        HandshakeStatus h = e.getHandshakeStatus();
        return h != HandshakeStatus.NOT_HANDSHAKING && h != HandshakeStatus.FINISHED;
    }
}
