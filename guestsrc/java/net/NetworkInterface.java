/*
 * Copyright (c) 2000, 2025, Oracle and/or its affiliates. All rights reserved.
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

/*
 * joe-ng overlay, taken from the JDK 26 source (rule 3). Every member is stock except the natives, which are
 * Java over the WiFi stack (see the joe-ng comment above getAll), and the one native that stays, wifi0, which
 * reads the stack's lease.
 */
package java.net;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static jdk.internal.util.Exceptions.filterNonSocketInfo;
import static jdk.internal.util.Exceptions.formatMsg;

/**
 * This class represents a Network Interface.
 * <p>
 * A Network Interface is an abstraction encapsulating
 * the characteristics of a Network Interface Controller, or
 * Virtual Network adapter, which is a system hardware/software
 * component connecting a computer, or host system, to a computer
 * network. A Network Interface can be physical or virtual.
 * A Network Interface has a name, zero or more assigned
 * {@linkplain InetAddress IP addresses}, zero or more {@linkplain
 * InterfaceAddress MAC Addresses}, and may have an index.
 * The name is highly platform specific but a name such as "le0"
 * is typical; it may not be unique. The index is a highly platform
 * specific number that identifies the interface. The network
 * configuration may change during the lifetime of the JVM.
 * For example, the set of IP addresses assigned to a network
 * interface can be transient and dynamically allocated, and may
 * change at any time.
 * <p>
 * When obtaining a {@code NetworkInterface} instance, part of its
 * configuration (such as its name and the list of assigned IP addresses),
 * is reflective of its configuration at creation time.
 * Obtaining an updated view of the network configuration may require
 * looking up a network interface again in order to obtain a new instance.
 * <p>
 * Network interface instances are typically used to identify the local
 * interface on which a multicast group is joined.
 *
 * @apiNote <a id="lookup"></a>Several static methods in this class are
 * factory methods, returning a new instance of a {@code NetworkInterface},
 * reflecting the configuration at the time of instantiation.
 * The network configuration may change at any time, and as such,
 * these methods may need to be invoked again in order to obtain
 * a more up-to-date view of the network interfaces.
 * In particular, there is no guarantee that the same interface will be
 * found at the same index, or that the same network addresses will be
 * bound to the interface, if the network configuration of the system
 * has changed.
 *
 * @since 1.4
 */
public final class NetworkInterface {
    private String name;
    private String displayName;
    private int index;
    private InetAddress[] addrs;
    private InterfaceAddress[] bindings;
    private NetworkInterface[] childs;
    private NetworkInterface parent = null;
    private boolean virtual = false;
    private static final NetworkInterface defaultInterface;

    static {
        jdk.internal.loader.BootLoader.loadLibrary("net");

        init();
        defaultInterface = DefaultInterface.getDefault();
    }

    /**
     * Returns an NetworkInterface object with index set to 0 and name to null.
     * Setting such an interface on a MulticastSocket will cause the
     * kernel to choose one interface for sending multicast packets.
     *
     */
    NetworkInterface() {
    }

    NetworkInterface(String name, int index, InetAddress[] addrs) {
        this.name = name;
        this.index = index;
        this.addrs = addrs;
    }

    /**
     * Get the name of this network interface.
     *
     * @return the name of this network interface
     */
    public String getName() {
            return name;
    }

    /**
     * Get an Enumeration of the InetAddresses bound to this network interface.
     *
     * @implNote
     * The returned enumeration contains the InetAddresses that were bound to
     * the interface at the time the {@linkplain #getNetworkInterfaces()
     * interface configuration was read}
     *
     * @return an Enumeration object with the InetAddresses bound to this
     * network interface
     * @see #inetAddresses()
     */
    public Enumeration<InetAddress> getInetAddresses() {
        return enumerationFromArray(addrs);
    }

    /**
     * Get a Stream of the InetAddresses bound to this network interface.
     *
     * @implNote
     * The stream contains the InetAddresses that were bound to the
     * interface at the time the {@linkplain #getNetworkInterfaces()
     * interface configuration was read}
     *
     * @return a Stream object with the InetAddresses bound to this network interface
     * @since 9
     */
    public Stream<InetAddress> inetAddresses() {
        return streamFromArray(addrs);
    }

    /**
     * Get a List of the {@code InterfaceAddresses} of this network interface.
     *
     * @return a {@code List} object with the InterfaceAddress of this
     * network interface
     *
     * @since 1.6
     */
    public List<InterfaceAddress> getInterfaceAddresses() {
        return bindings == null ? List.of() : List.of(bindings);
    }

    /**
     * Get an Enumeration with all the subinterfaces (also known as virtual
     * interfaces) attached to this network interface.
     * <p>
     * For instance eth0:1 will be a subinterface to eth0.
     *
     * @return an Enumeration object with all of the subinterfaces
     * of this network interface
     * @see #subInterfaces()
     * @since 1.6
     */
    public Enumeration<NetworkInterface> getSubInterfaces() {
        return enumerationFromArray(childs);
    }

    /**
     * Get a Stream of all subinterfaces (also known as virtual
     * interfaces) attached to this network interface.
     *
     * @return a Stream object with all of the subinterfaces
     * of this network interface
     * @since 9
     */
    public Stream<NetworkInterface> subInterfaces() {
        return streamFromArray(childs);
    }

    /**
     * Returns the parent NetworkInterface of this interface if this is
     * a subinterface, or {@code null} if it is a physical
     * (non virtual) interface or has no parent.
     *
     * @return The {@code NetworkInterface} this interface is attached to.
     * @since 1.6
     */
    public NetworkInterface getParent() {
        return parent;
    }

    /**
     * Returns the index of this network interface. The index is an integer greater
     * or equal to zero, or {@code -1} for unknown. This is a system specific value
     * and interfaces with the same name can have different indexes on different
     * machines.
     *
     * @return the index of this network interface or {@code -1} if the index is
     *         unknown
     * @see #getByIndex(int)
     * @since 1.7
     */
    public int getIndex() {
        return index;
    }

    /**
     * Get the display name of this network interface.
     * A display name is a human readable String describing the network
     * device.
     *
     * @return a non-empty string representing the display name of this network
     *         interface, or null if no display name is available.
     */
    public String getDisplayName() {
        /* strict TCK conformance */
        return "".equals(displayName) ? null : displayName;
    }

    /**
     * Searches for the network interface with the specified name.
     *
     * @apiNote
     * The returned interface instance may reflect a snapshot of the
     * configuration taken at the time the instance is created.
     * See the general discussion of {@linkplain NetworkInterface##lookup
     * snapshots and configuration} for the semantics of the returned interface.
     *
     * @param   name
     *          The name of the network interface.
     *
     * @return  A {@code NetworkInterface} with the specified name,
     *          or {@code null} if there is no network interface
     *          with the specified name.
     *
     * @throws  SocketException
     *          If an I/O error occurs.
     *
     * @throws  NullPointerException
     *          If the specified name is {@code null}.
     */
    public static NetworkInterface getByName(String name) throws SocketException {
        if (name == null)
            throw new NullPointerException();
        return getByName0(name);
    }

    /**
     * Get a network interface given its index.
     *
     * @apiNote
     * The returned interface instance may reflect a snapshot of the
     * configuration taken at the time the instance is created.
     * See the general discussion of {@linkplain NetworkInterface##lookup
     * snapshots and configuration} for the semantics of the returned interface.
     *
     * @param index an integer, the index of the interface
     * @return the NetworkInterface obtained from its index, or {@code null} if
     *         there is no interface with such an index on the system
     * @throws  SocketException  if an I/O error occurs.
     * @throws  IllegalArgumentException if index has a negative value
     * @see #getIndex()
     * @since 1.7
     */
    public static NetworkInterface getByIndex(int index) throws SocketException {
        if (index < 0)
            throw new IllegalArgumentException("Interface index can't be negative");
        return getByIndex0(index);
    }

    /**
     * Convenience method to search for a network interface that
     * has the specified Internet Protocol (IP) address bound to
     * it.
     * <p>
     * If the specified IP address is bound to multiple network
     * interfaces it is not defined which network interface is
     * returned.
     *
     * @apiNote
     * The returned interface instance may reflect a snapshot of the
     * configuration taken at the time the instance is created.
     * See the general discussion of {@linkplain NetworkInterface##lookup
     * snapshots and configuration} for the semantics of the returned interface.
     *
     * @param   addr
     *          The {@code InetAddress} to search with.
     *
     * @return  A {@code NetworkInterface}
     *          or {@code null} if there is no network interface
     *          with the specified IP address.
     *
     * @throws  SocketException
     *          If an I/O error occurs.
     *
     * @throws  NullPointerException
     *          If the specified address is {@code null}.
     */
    public static NetworkInterface getByInetAddress(InetAddress addr) throws SocketException {
        if (addr == null) {
            throw new NullPointerException();
        }

        if (addr.holder.family == InetAddress.IPv4) {
            if (!(addr instanceof Inet4Address)) {
                throw new IllegalArgumentException("invalid family type: "
                        + addr.holder.family);
            }
        } else if (addr.holder.family == InetAddress.IPv6) {
            if (!(addr instanceof Inet6Address)) {
                throw new IllegalArgumentException("invalid family type: "
                        + addr.holder.family);
            }
        } else {
            throw new IllegalArgumentException(
                formatMsg("invalid address type%s",
                          filterNonSocketInfo(addr.toString()).prefixWith(": ")));
        }
        return getByInetAddress0(addr);
    }

    /**
     * Returns an {@code Enumeration} of all the interfaces on this machine. The
     * {@code Enumeration} contains at least one element, possibly representing
     * a loopback interface that only supports communication between entities on
     * this machine.
     *
     * @apiNote
     * This method can be used in combination with
     * {@link #getInetAddresses()} to obtain all IP addresses for this node.
     * <p>
     * The returned interface instances may reflect a snapshot of the
     * configuration taken at the time the instance is created.
     * See the general discussion of {@linkplain NetworkInterface##lookup
     * snapshots and configuration} for the semantics of the returned interface.
     *
     * @return an Enumeration of NetworkInterfaces found on this machine
     * @throws     SocketException  if an I/O error occurs,
     *             or if the platform does not have at least one configured
     *             network interface.
     * @see #networkInterfaces()
     */
    public static Enumeration<NetworkInterface> getNetworkInterfaces()
        throws SocketException {
        NetworkInterface[] netifs = getAll();
        if (netifs != null && netifs.length > 0) {
            return enumerationFromArray(netifs);
        } else {
            throw new SocketException("No network interfaces configured");
        }
    }

    /**
     * Returns a {@code Stream} of all the interfaces on this machine.  The
     * {@code Stream} contains at least one interface, possibly representing a
     * loopback interface that only supports communication between entities on
     * this machine.
     *
     * @apiNote This method can be used in combination with
     * {@link #inetAddresses()} to obtain a stream of all IP addresses for
     * this node, for example:
     * <pre> {@code
     * Stream<InetAddress> addrs = NetworkInterface.networkInterfaces()
     *     .flatMap(NetworkInterface::inetAddresses);
     * }</pre>
     * <p>
     * The returned interface instances may reflect a snapshot of the
     * configuration taken at the time the instance is created.
     * See the general discussion of {@linkplain NetworkInterface##lookup
     * snapshots and configuration} for the semantics of the returned interface.
     *
     * @return a Stream of NetworkInterfaces found on this machine
     * @throws     SocketException  if an I/O error occurs,
     *             or if the platform does not have at least one configured
     *             network interface.
     * @since 9
     */
    public static Stream<NetworkInterface> networkInterfaces()
        throws SocketException {
        NetworkInterface[] netifs = getAll();
        if (netifs != null && netifs.length > 0) {
            return streamFromArray(netifs);
        }  else {
            throw new SocketException("No network interfaces configured");
        }
    }

    /**
     * Checks if the given address is bound to any of the interfaces on this
     * machine.
     *
     * @param   addr
     *          The {@code InetAddress} to search with.
     * @return  true iff the addr parameter is currently bound to one of
     *          the interfaces on this machine.
     *
     * @throws  SocketException
     *          If an I/O error occurs.
     */
    /* package-private */ static boolean isBoundInetAddress(InetAddress addr)
        throws SocketException {
        return boundInetAddress0(addr);
    }

    private static <T> Enumeration<T> enumerationFromArray(T[] a) {
        return new Enumeration<>() {
            int i = 0;

            @Override
            public T nextElement() {
                if (i < a.length) {
                    return a[i++];
                } else {
                    throw new NoSuchElementException();
                }
            }

            @Override
            public boolean hasMoreElements() {
                return i < a.length;
            }
        };
    }

    private static <T> Stream<T> streamFromArray(T[] a) {
        return StreamSupport.stream(
                Spliterators.spliterator(
                        a,
                        Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL),
                false);
    }

    // ---- joe-ng: the natives of this class are Java over the WiFi stack's lease (wifi0), their semantics read
    // from unix/native/libnet/NetworkInterface.c. This VM has ONE interface, the CYW43 station: it exists once
    // DHCP has leased an address and ARP has found the gateway (the point net.Ip publishes the config and the
    // TCP stack can use it), and there is no loopback because the stack cannot carry 127.0.0.1. Without WiFi
    // (QEMU, or net=0) the list is EMPTY, which stock reports as "No network interfaces configured".

    /** The one interface's name: what Linux calls a Pi's WiFi station. */
    private static final String WIFI_NAME = "wlan0";

    /** Its index: the only interface, so the first. */
    private static final int WIFI_INDEX = 1;

    /** The CYW43 data path carries 802.3 frames: Ethernet's MTU. */
    private static final int WIFI_MTU = 1500;

    /**
     * The WiFi stack's view (VMNatives.netifState): key 0 the leased IPv4 address, 1 the subnet mask (both
     * big-endian ints, 0 when there is no lease), 2 the station MAC in the low 48 bits (0 when not read).
     */
    private static native long wifi0(long key);

    /** As the C's createNetworkInterface builds it: displayName = name, no children, one binding per address. */
    private static NetworkInterface wifi()
    {
        int ip = (int) wifi0(0);
        if (ip == 0)
        {
            return null;
        }
        int mask = (int) wifi0(1);
        Inet4Address addr = new Inet4Address(null, ip);
        NetworkInterface ni = new NetworkInterface(WIFI_NAME, WIFI_INDEX, new InetAddress[] { addr });
        ni.displayName = WIFI_NAME;
        ni.childs = new NetworkInterface[0];
        Inet4Address broadcast = new Inet4Address(null, ip | ~mask);
        short prefix = (short) Integer.bitCount(mask);
        ni.bindings = new InterfaceAddress[] { new InterfaceAddress(addr, broadcast, prefix) };
        return ni;
    }

    /** The C's getFlags failure for an interface that is not there (any more). */
    private static void requireWifi(String name) throws SocketException
    {
        if (!WIFI_NAME.equals(name) || wifi0(0) == 0)
        {
            throw new SocketException("ioctl(SIOCGIFFLAGS) failed");
        }
    }

    private static NetworkInterface[] getAll() throws SocketException
    {
        NetworkInterface ni = wifi();
        if (ni == null)
        {
            return new NetworkInterface[0];
        }
        return new NetworkInterface[] { ni };
    }

    private static NetworkInterface getByName0(String name) throws SocketException
    {
        if (name == null)
        {
            throw new NullPointerException("network interface name is NULL");
        }
        NetworkInterface ni = wifi();
        if (ni != null && ni.name.equals(name))
        {
            return ni;
        }
        return null;
    }

    private static NetworkInterface getByIndex0(int index) throws SocketException
    {
        NetworkInterface ni = wifi();
        if (ni != null && ni.index == index)
        {
            return ni;
        }
        return null;
    }

    private static boolean boundInetAddress0(InetAddress addr) throws SocketException
    {
        return getByInetAddress0(addr) != null;
    }

    private static NetworkInterface getByInetAddress0(InetAddress addr) throws SocketException
    {
        NetworkInterface ni = wifi();
        if (ni != null && addr instanceof Inet4Address && ni.addrs[0].equals(addr))
        {
            return ni;
        }
        return null;
    }

    /**
     * Returns whether a network interface is up and running.
     *
     * @return  {@code true} if the interface is up and running.
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */

    public boolean isUp() throws SocketException {
        return isUp0(name, index);
    }

    /**
     * Returns whether a network interface is a loopback interface.
     *
     * @return  {@code true} if the interface is a loopback interface.
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */

    public boolean isLoopback() throws SocketException {
        return isLoopback0(name, index);
    }

    /**
     * Returns whether a network interface is a point to point interface.
     * A typical point to point interface would be a PPP connection through
     * a modem.
     *
     * @return  {@code true} if the interface is a point to point
     *          interface.
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */

    public boolean isPointToPoint() throws SocketException {
        return isP2P0(name, index);
    }

    /**
     * Returns whether a network interface supports multicasting or not.
     *
     * @return  {@code true} if the interface supports Multicasting.
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */

    public boolean supportsMulticast() throws SocketException {
        return supportsMulticast0(name, index);
    }

    /**
     * Returns the hardware address (usually MAC) of the interface if it
     * has one and if it can be accessed given the current privileges.
     *
     * @return  a byte array containing the address, or {@code null} if
     *          the address doesn't exist or is not accessible
     *
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */
    public byte[] getHardwareAddress() throws SocketException {
        if (isLoopback0(name, index)) {
            return null;
        }
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet4Address) {
                return getMacAddr0(((Inet4Address)addr).getAddress(), name, index);
            }
        }
        return getMacAddr0(null, name, index);
    }

    /**
     * Returns the Maximum Transmission Unit (MTU) of this interface.
     *
     * @return the value of the MTU for that interface.
     * @throws          SocketException if an I/O error occurs.
     * @since 1.6
     */
    public int getMTU() throws SocketException {
        return getMTU0(name, index);
    }

    /**
     * Returns whether this interface is a virtual interface (also called
     * subinterface).
     * Virtual interfaces are, on some systems, interfaces created as a child
     * of a physical interface and given different settings (like address or
     * MTU). Usually the name of the interface will the name of the parent
     * followed by a colon (:) and a number identifying the child since there
     * can be several virtual interfaces attached to a single physical
     * interface.
     *
     * @return {@code true} if this interface is a virtual interface.
     * @since 1.6
     */
    public boolean isVirtual() {
        return virtual;
    }

    // joe-ng: the flag natives, for the one interface (see wifi0). Up while it is listed; neither loopback nor
    // point-to-point. Multicast is FALSE: the flag says what the interface can do, and this stack has no IGMP and
    // no multicast receive, so a socket asked to join a group here could not. A name that is not the interface
    // throws as the C's ioctl does.
    private static boolean isUp0(String name, int ind) throws SocketException
    {
        requireWifi(name);
        return true;
    }

    private static boolean isLoopback0(String name, int ind) throws SocketException
    {
        requireWifi(name);
        return false;
    }

    private static boolean supportsMulticast0(String name, int ind) throws SocketException
    {
        requireWifi(name);
        return false;
    }

    private static boolean isP2P0(String name, int ind) throws SocketException
    {
        requireWifi(name);
        return false;
    }

    private static byte[] getMacAddr0(byte[] inAddr, String name, int ind) throws SocketException
    {
        requireWifi(name);
        long mac = wifi0(2);
        if (mac == 0)
        {
            return null;                                   // the C answers null for an all-zero address
        }
        byte[] b = new byte[6];
        int i = 0;
        while (i < 6)
        {
            b[i] = (byte) (mac >>> (40 - 8 * i));
            i += 1;
        }
        return b;
    }

    private static int getMTU0(String name, int ind) throws SocketException
    {
        requireWifi(name);
        return WIFI_MTU;
    }

    /**
     * Compares this object against the specified object.
     * The result is {@code true} if and only if the argument is
     * not {@code null} and it represents the same NetworkInterface
     * as this object.
     * <p>
     * Two instances of {@code NetworkInterface} represent the same
     * NetworkInterface if both the name and the set of {@code InetAddress}es
     * bound to the interfaces are equal.
     *
     * @apiNote two {@code NetworkInterface} objects referring to the same
     * underlying interface may not compare equal if the addresses
     * of the underlying interface are being dynamically updated by
     * the system.
     *
     * @param   obj   the object to compare against.
     * @return  {@code true} if the objects are the same;
     *          {@code false} otherwise.
     * @see     java.net.InetAddress#getAddress()
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof NetworkInterface that)) {
            return false;
        }
        if (!Objects.equals(this.name, that.name)) {
            return false;
        }

        if (this.addrs == null) {
            return that.addrs == null;
        } else if (that.addrs == null) {
            return false;
        }

        /* Both addrs not null. Compare number of addresses */

        if (this.addrs.length != that.addrs.length) {
            return false;
        }

        for (InetAddress thisAddr : this.addrs) {
            boolean found = false;
            for (InetAddress thatAddr : that.addrs) {
                if (thisAddr.equals(thatAddr)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    public String toString() {
        String result = "name:";
        result += name == null? "null": name;
        if (displayName != null) {
            result += " (" + displayName + ")";
        }
        return result;
    }

    // joe-ng: the C caches JNI class, field and method IDs (and initInetAddressIDs does the same for
    // InetAddress); with no JNI there is nothing to cache, so empty is the correct semantics here.
    private static void init()
    {
    }

    /**
     * Returns the default network interface of this system
     *
     * @return the default interface
     */
    static NetworkInterface getDefault() {
        return defaultInterface;
    }
}
