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
package hello;

/**
 * Leaves by {@code System.exit} or {@code Runtime.halt}, run with {@code main=hello.Quit} and one argument naming
 * how: {@code exit} (the default), {@code halt}, {@code catch} (a {@code catch (Exception)} around the exit),
 * {@code thread} (another thread exits, main carries on) or {@code hook} (registers a shutdown hook first).
 */
public class Quit
{
    public static void main(String[] args) throws Exception
    {
        String how = args.length > 0 ? args[0] : "exit";
        System.out.println("quit: " + how);
        if (how.equals("halt"))
        {
            Runtime.getRuntime().halt(4);
        }
        else if (how.equals("catch"))
        {
            try
            {
                System.exit(6);
            }
            catch (Exception e)
            {
                System.out.println("quit: an Exception handler caught the exit");
            }
        }
        else if (how.equals("thread"))
        {
            Thread t = new Thread(() -> System.exit(5), "quitter");
            t.start();
            t.join();
            System.out.println("quit: main carries on after its thread exited");
            return;
        }
        else if (how.equals("hook"))
        {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("quit: hook ran")));
            System.out.println("quit: hook registered");
            System.exit(0);
        }
        else
        {
            System.exit(3);
        }
        System.out.println("quit: still running after the exit");
    }
}
