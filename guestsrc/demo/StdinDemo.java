/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-13
 */
package demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Reads {@code System.in} line by line and echoes each line back, until end-of-file (Ctrl-D on an empty line)
 * or the line {@code quit}. Boot it with {@code main=demo/StdinDemo} and {@code stdin=uart} in
 * {@code /etc/init}; without the flag System.in is empty and it prints {@code lines=0} at once.
 */
public final class StdinDemo
{
    private StdinDemo()
    {
    }

    public static void main(String[] args) throws IOException
    {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        int lines = 0;
        System.out.println("type lines; Ctrl-D or 'quit' ends");
        while (true)
        {
            System.out.print("> ");
            String s = in.readLine();
            if (s == null || s.equals("quit"))
            {
                break;
            }
            lines += 1;
            System.out.println("got [" + s + "] length=" + s.length());
        }
        System.out.println("lines=" + lines);
    }
}
