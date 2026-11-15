/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package bench;

import java.net.ServerSocket;
import java.net.Socket;

/**
 * A run that a stop cannot end until someone connects: it waits in {@code ServerSocket.accept}, inside the JDK,
 * where no stop poll is. Stopped while it waits, its stop stays PENDING -- {@code Heap.STOP_CELL} nonzero -- which
 * is the condition under which every other run's polls call into the VM. {@code bench.Main} measures that case
 * beside it. Connecting to the port lets accept return, and the loop's poll ends it. Args: {@code [port]}.
 */
public class Block
{
    public static void main(String[] args) throws Exception
    {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        ServerSocket server = new ServerSocket(port);
        System.out.println("block: waiting on port " + port);
        while (true)
        {
            Socket s = server.accept();
            System.out.println("block: a connection");
            s.close();
        }
    }
}
