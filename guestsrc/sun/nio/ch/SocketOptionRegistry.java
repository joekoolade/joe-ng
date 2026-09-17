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
package sun.nio.ch;

import java.net.ProtocolFamily;
import java.net.SocketOption;

/**
 * Name-winning overlay. Stock {@code SocketOptionRegistry} builds a big option map in a
 * ConcurrentHashMap-backed {@code <clinit>}, which drags the whole streams/ForkJoin/EnumMap closure into the
 * demand-load (it OOM'd). The only path that reaches it on a blocking client socket is {@code close()}'s
 * SO_LINGER check ({@code Net.getSocketOption -> findOption}), and the returned {@link OptionKey}'s
 * level/name are ignored by our {@code Net.getIntOption0} stub (which returns 0). A fixed dummy key suffices.
 */
public class SocketOptionRegistry
{
    private SocketOptionRegistry()
    {
    }

    public static OptionKey findOption(SocketOption<?> name, ProtocolFamily family)
    {
        return new OptionKey(1, 13);   // SOL_SOCKET, SO_LINGER -- value ignored by getIntOption0 -> 0
    }
}
