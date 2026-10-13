/*
 * Copyright (c) 2002, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.net;
import java.io.IOException;
import java.net.spi.InetAddressResolver.LookupPolicy;

import static java.net.spi.InetAddressResolver.LookupPolicy.IPV4;
import static jdk.internal.util.Exceptions.filterNonSocketInfo;
import static jdk.internal.util.Exceptions.formatMsg;

/*
 * Package private implementation of InetAddressImpl for IPv4.
 *
 * <p>joe-ng overlay, taken from the JDK 26 source (rule 3). Every member is stock except
 * {@code lookupAllHostAddr(String)}, which is a native there (getaddrinfo in Inet4AddressImpl.c) and Java here:
 * it asks the WiFi stack's DNS resolver through the provided native {@code resolve0} and builds the
 * {@code Inet4Address} the C would have. The resolver answers ONE A record, so the array has one element. The
 * other three natives ({@code getLocalHostName}, {@code getHostByAddr}, {@code isReachable0}) stay unprovided:
 * a call halts naming the method rather than answering.
 *
 * @since 1.4
 */
final class Inet4AddressImpl implements InetAddressImpl {
    public native String getLocalHostName() throws UnknownHostException;
    public InetAddress[] lookupAllHostAddr(String hostname, LookupPolicy lookupPolicy)
            throws UnknownHostException {
        if ((lookupPolicy.characteristics() & IPV4) == 0) {
            throw new UnknownHostException(formatMsg("%s", filterNonSocketInfo(hostname)));
        }
        return lookupAllHostAddr(hostname);
    }
    private InetAddress[] lookupAllHostAddr(String hostname) throws UnknownHostException {
        // joe-ng: stock is a native over getaddrinfo. Same contract: the hostname's addresses, each carrying the
        // name it was looked up by, or UnknownHostException naming the host (filtered the way the C's is).
        int addr = resolve0(hostname.getBytes(sun.nio.cs.ISO_8859_1.INSTANCE));
        if (addr == 0) {
            throw new UnknownHostException(formatMsg("%s", filterNonSocketInfo(hostname)));
        }
        return new InetAddress[] { new Inet4Address(hostname, addr) };
    }

    /** joe-ng: WiFi DNS (VMNatives.dnsResolve): the big-endian IPv4 address, or 0 when there is no answer. */
    private static native int resolve0(byte[] host);
    public native String getHostByAddr(byte[] addr) throws UnknownHostException;
    private native boolean isReachable0(byte[] addr, int timeout, byte[] ifaddr, int ttl) throws IOException;

    public synchronized InetAddress anyLocalAddress() {
        if (anyLocalAddress == null) {
            anyLocalAddress = new Inet4Address(); // {0x00,0x00,0x00,0x00}
            anyLocalAddress.holder().hostName = "0.0.0.0";
        }
        return anyLocalAddress;
    }

    public synchronized InetAddress loopbackAddress() {
        if (loopbackAddress == null) {
            byte[] loopback = {0x7f,0x00,0x00,0x01};
            loopbackAddress = new Inet4Address("localhost", loopback);
        }
        return loopbackAddress;
    }

  public boolean isReachable(InetAddress addr, int timeout, NetworkInterface netif, int ttl) throws IOException {
      byte[] ifaddr = null;
      if (netif != null) {
          /*
           * Let's make sure we use an address of the proper family
           */
          java.util.Enumeration<InetAddress> it = netif.getInetAddresses();
          InetAddress inetaddr = null;
          while (!(inetaddr instanceof Inet4Address) &&
                 it.hasMoreElements())
              inetaddr = it.nextElement();
          if (inetaddr instanceof Inet4Address)
              ifaddr = inetaddr.getAddress();
      }
      return isReachable0(addr.getAddress(), timeout, ifaddr, ttl);
  }
    private InetAddress      anyLocalAddress;
    private InetAddress      loopbackAddress;
}
