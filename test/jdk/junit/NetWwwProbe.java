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

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;

/**
 * sun/net/www/ is no longer denied: the http, https, ftp, mailto and jrt URL handlers and the HTTP client load (an
 * https CONNECTION needs javax/net/ssl and sun/security, so no arm opens one). No arm
 * touches the network -- connections are created and configured but never connected -- so the host answers the same.
 * The jrt arm is the suite's old CTOR SKIPPED (the jrt Handler's constructor was skipped while it was denied), and the
 * HTTP-proxy socket arm is the one #467 left throwing InternalError because its initializer reflects on the HTTP
 * client. Each arm runs on its own and prints what it got.
 */
public class NetWwwProbe
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

    static URL url(String s) throws Exception
    {
        return new URI(s).toURL();
    }

    public static void main(String[] args)
    {
        report("http connection", () ->
        {
            URLConnection c = url("http://example.com/a?b=1").openConnection();
            return c.getClass().getName() + " connected-host=" + c.getURL().getHost();
        });
        report("http configure", () ->
        {
            HttpURLConnection c = (HttpURLConnection) url("http://example.com/").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Probe", "one");
            c.addRequestProperty("X-Probe", "two");
            c.setConnectTimeout(1234);
            c.setInstanceFollowRedirects(false);
            return c.getRequestMethod() + " " + c.getRequestProperties().get("X-Probe") + " timeout="
                    + c.getConnectTimeout() + " follow=" + c.getInstanceFollowRedirects();
        });
        report("http bad method", () ->
        {
            HttpURLConnection c = (HttpURLConnection) url("http://example.com/").openConnection();
            c.setRequestMethod("BREW");
            return "accepted";
        });
        report("https url", () ->
        {
            URL u = url("https://example.com/x");
            return u.getProtocol() + " port=" + u.getDefaultPort();
        });
        report("ftp connection", () -> url("ftp://example.com/f.txt").openConnection().getClass().getName());
        report("mailto connection", () -> url("mailto:a@example.com").openConnection().getClass().getName());
        report("jrt url", () ->
        {
            URL u = url("jrt:/java.base/java/lang/Object.class");
            return u.getProtocol() + " " + u.getPath();
        });
        report("chunked stream class", () ->
        {
            Class<?> c = Class.forName("sun.net.www.http.ChunkedInputStream", false, null);
            return c.getSuperclass().getName();
        });
        report("HTTP proxy socket", () ->
        {
            Socket s = new Socket(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy", 3128)));
            return "connected=" + s.isConnected() + " closed=" + s.isClosed();
        });
        report("HTTP proxy socket again", () ->
        {
            Socket s = new Socket(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy", 3128)));
            return "connected=" + s.isConnected();
        });
        System.out.println("NetWwwProbe done");
    }
}
