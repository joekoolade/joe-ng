package demo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * M3 networking demo: a plain HTTP GET over STOCK {@code java.net.Socket} / {@code sun.nio.ch.NioSocketImpl}
 * running on bare metal, backed by joe-ng's net.Tcp stack. Resolves the host (InetAddress overlay -> WiFi
 * DNS), opens a socket, writes the request, reads the response, closes. Launched via the /etc/init manifest
 * with {@code net=1} so the OS brings the WiFi interface up first.
 */
public class NetDemo
{
    public static void main(String[] args) throws Exception
    {
        String host = args.length > 0 ? args[0] : "example.com";
        Socket s = new Socket(host, 80);
        System.out.println("socket connected");

        OutputStream o = s.getOutputStream();
        o.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes());
        System.out.println("GET sent -----");

        InputStream in = s.getInputStream();
        byte[] buf = new byte[2048];
        int total = 0;
        int n = in.read(buf);
        while (n > 0)
        {
            System.out.print(new String(buf, 0, n));
            total = total + n;
            n = in.read(buf);
        }
        s.close();
        System.out.println();
        System.out.print("----- http done bytes=");
        System.out.println(total);
    }
}
