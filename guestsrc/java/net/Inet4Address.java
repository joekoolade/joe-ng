/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package java.net;

/**
 * Name-winning {@code Inet4Address} overlay for joe-ng. Stock {@code Inet4Address} stores its state in the
 * {@code InetAddress.holder()} (an {@code InetAddressHolder} the minimal overlay doesn't carry). We only need
 * the class to EXIST as a subtype of {@link InetAddress}: {@code java.net.Socket.checkAddress} (and
 * {@code sun.nio.ch.Net.checkAddress}) reject any address that is not {@code instanceof Inet4Address ||
 * instanceof Inet6Address}. So {@link InetAddress#getByName} returns an {@code Inet4Address}, and this overlay
 * inherits the whole (big-endian int) implementation from {@link InetAddress} -- it adds nothing but identity.
 */
class Inet4Address extends InetAddress
{
    Inet4Address()
    {
        super();
    }
}
