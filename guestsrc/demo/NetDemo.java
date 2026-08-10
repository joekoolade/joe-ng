package demo;

import java.net.InetAddress;

/**
 * M3 networking demo. Step 1: resolve a hostname through stock {@code java.net.InetAddress} (the metal
 * overlay -> WiFi DNS). Later steps add {@code new Socket(host,80)} + an HTTP GET over stock
 * {@code sun.nio.ch}. Launched via the /etc/init manifest with {@code net=1}, so the OS brings the WiFi
 * interface up first.
 */
public class NetDemo
{
    public static void main(String[] args) throws Exception
    {
        String host = args.length > 0 ? args[0] : "example.com";
        InetAddress a = InetAddress.getByName(host);
        System.out.print("resolved ");
        System.out.print(host);
        System.out.print(" = ");
        System.out.println(a.getHostAddress());
    }
}
