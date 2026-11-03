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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A complete TLS session with no network: a client and a server SSLEngine in the same VM, their records passed
 * between two ByteBuffers. It is the whole of sun.security.ssl -- key exchange, the certificate chain, the
 * Finished MACs, record encryption -- run where QEMU can reach it, since QEMU has no network and an HTTPS GET can
 * only be tried on the board. The server key is a real one (keytool, EC secp256r1, CN=localhost, SAN
 * dns:localhost), embedded as Base64. The arms also load the DEFAULT trust store (java.home/lib/security/cacerts)
 * and check that it REJECTS this self-signed server and that endpoint identification rejects the wrong host.
 * Each arm runs on its own and prints what it got.
 */
public class SslEngineProbe
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

    /** PKCS12, store and key password "serverpass", alias "server". */
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

    static KeyStore serverStore() throws Exception
    {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(SERVER_P12)), "serverpass".toCharArray());
        return ks;
    }

    static SSLContext serverContext() throws Exception
    {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(serverStore(), "serverpass".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** A client that trusts exactly the server's certificate. */
    static SSLContext pinnedClient() throws Exception
    {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", serverStore().getCertificate("server"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** Run tasks the engine handed back. */
    static void runTasks(SSLEngine e)
    {
        Runnable r;
        while ((r = e.getDelegatedTask()) != null)
        {
            r.run();
        }
    }

    static boolean busy(SSLEngine e)
    {
        HandshakeStatus h = e.getHandshakeStatus();
        return h != HandshakeStatus.NOT_HANDSHAKING && h != HandshakeStatus.FINISHED;
    }

    /**
     * Shuttle records between the two engines until both finish the handshake; then send {@code msg} client ->
     * server and the reply server -> client. Answers "protocol cipher | server got X | client got Y".
     */
    static String session(SSLEngine client, SSLEngine server, String msg) throws Exception
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
        while ((busy(client) || busy(server)) && rounds < 200)
        {
            rounds += 1;
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
            return "handshake did not finish: client " + client.getHandshakeStatus() + " server "
                    + server.getHandshakeStatus();
        }
        clientIn.clear();
        serverIn.clear();
        client.wrap(ByteBuffer.wrap(msg.getBytes(StandardCharsets.UTF_8)), c2s);
        c2s.flip();
        server.unwrap(c2s, serverIn);
        c2s.compact();
        serverIn.flip();
        String gotServer = StandardCharsets.UTF_8.decode(serverIn).toString();
        server.wrap(ByteBuffer.wrap(("echo " + gotServer).getBytes(StandardCharsets.UTF_8)), s2c);
        s2c.flip();
        SSLEngineResult r = client.unwrap(s2c, clientIn);
        while (r.bytesProduced() == 0 && s2c.hasRemaining())
        {
            r = client.unwrap(s2c, clientIn);
        }
        clientIn.flip();
        String gotClient = StandardCharsets.UTF_8.decode(clientIn).toString();
        return client.getSession().getProtocol() + " " + client.getSession().getCipherSuite() + " | server got "
                + gotServer + " | client got " + gotClient;
    }

    static SSLEngine clientEngine(SSLContext ctx, String host, String protocol)
    {
        SSLEngine e = ctx.createSSLEngine(host, 443);
        e.setUseClientMode(true);
        if (protocol != null)
        {
            e.setEnabledProtocols(new String[] { protocol });
        }
        return e;
    }

    static SSLEngine serverEngine(SSLContext ctx)
    {
        SSLEngine e = ctx.createSSLEngine();
        e.setUseClientMode(false);
        return e;
    }

    public static void main(String[] args)
    {
        report("server key", () ->
        {
            KeyStore ks = serverStore();
            X509Certificate c = (X509Certificate) ks.getCertificate("server");
            return ks.getKey("server", "serverpass".toCharArray()).getAlgorithm() + " "
                    + c.getSubjectX500Principal().getName() + " san=" + c.getSubjectAlternativeNames();
        });
        report("TLSv1.3 session", () -> session(clientEngine(pinnedClient(), "localhost", "TLSv1.3"),
                serverEngine(serverContext()), "hello over TLS 1.3"));
        report("TLSv1.2 session", () -> session(clientEngine(pinnedClient(), "localhost", "TLSv1.2"),
                serverEngine(serverContext()), "hello over TLS 1.2"));
        report("peer certificate", () ->
        {
            SSLEngine c = clientEngine(pinnedClient(), "localhost", null);
            session(c, serverEngine(serverContext()), "x");
            return ((X509Certificate) c.getSession().getPeerCertificates()[0]).getSubjectX500Principal().getName();
        });
        report("hostname checked, right host", () ->
        {
            SSLEngine c = clientEngine(pinnedClient(), "localhost", null);
            SSLParameters p = c.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            c.setSSLParameters(p);
            return session(c, serverEngine(serverContext()), "named");
        });
        report("hostname checked, wrong host", () ->
        {
            SSLEngine c = clientEngine(pinnedClient(), "wrong.example", null);
            SSLParameters p = c.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            c.setSSLParameters(p);
            return session(c, serverEngine(serverContext()), "should not arrive");
        });
        report("default trust store", () ->
        {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager tm = (X509TrustManager) tmf.getTrustManagers()[0];
            return tm.getAcceptedIssuers().length > 100 ? "more than 100 roots" : tm.getAcceptedIssuers().length
                    + " roots";
        });
        report("default trust rejects self-signed", () ->
        {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            return session(clientEngine(ctx, "localhost", null), serverEngine(serverContext()), "untrusted");
        });
        System.out.println("SslEngineProbe done");
    }
}
