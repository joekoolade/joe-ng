/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-20
 */

/**
 * Object's monitor contract at the edges. {@code wait}/{@code notify}/{@code notifyAll} by a thread that does NOT
 * own the monitor throw {@code IllegalMonitorStateException}; a negative {@code wait} timeout throws
 * {@code IllegalArgumentException} -- checked BEFORE ownership, so it wins when both apply. The compiler lowers
 * these calls straight to VM helpers, past {@code Object.wait(long)}'s own check, so every form is exercised:
 * class-typed and interface-typed receivers, an array, a monitor held recursively, and the ordinary handshake
 * afterwards to show a refused call left the monitor usable.
 */
public class MonitorStateProbe
{
    interface Marker
    {
    }

    static class Box implements Marker
    {
        boolean ready;
    }

    public static void main(String[] args) throws Exception
    {
        Object lock = new Object();
        System.out.println("wait() unowned: " + attempt(() -> lock.wait()));
        System.out.println("wait(10) unowned: " + attempt(() -> lock.wait(10)));
        System.out.println("wait(0) unowned: " + attempt(() -> lock.wait(0)));
        System.out.println("wait(10,5) unowned: " + attempt(() -> lock.wait(10, 5)));
        System.out.println("notify unowned: " + attempt(() -> lock.notify()));
        System.out.println("notifyAll unowned: " + attempt(() -> lock.notifyAll()));
        System.out.println("wait(-1) unowned: " + attempt(() -> lock.wait(-1)));
        System.out.println("wait(-1) owned: " + attempt(() ->
        {
            synchronized (lock)
            {
                lock.wait(-1);
            }
        }));
        System.out.println("wait(0,-1) owned: " + attempt(() ->
        {
            synchronized (lock)
            {
                lock.wait(0, -1);
            }
        }));
        Object other = new Object();
        System.out.println("notify other's monitor: " + attempt(() ->
        {
            synchronized (lock)
            {
                other.notify();
            }
        }));
        Marker m = new Box();
        System.out.println("interface-typed notify unowned: " + attempt(() -> m.notify()));
        System.out.println("interface-typed wait unowned: " + attempt(() -> m.wait(5)));
        int[] arr = new int[1];
        System.out.println("array notifyAll unowned: " + attempt(() -> arr.notifyAll()));
        System.out.println("owned notify: " + attempt(() ->
        {
            synchronized (lock)
            {
                lock.notify();
                lock.notifyAll();
            }
        }));
        System.out.println("owned timed wait: " + attempt(() ->
        {
            synchronized (lock)
            {
                lock.wait(5);
            }
        }));
        System.out.println("recursive owned timed wait: " + attempt(() ->
        {
            synchronized (lock)
            {
                synchronized (lock)
                {
                    lock.wait(5);
                }
                lock.notify();
            }
        }));
        System.out.println("after exit, notify unowned: " + attempt(() -> lock.notify()));
        System.out.println("holdsLock outside: " + Thread.holdsLock(lock));

        Box box = (Box) m;
        Thread t = new Thread(() ->
        {
            synchronized (box)
            {
                box.ready = true;
                box.notifyAll();
            }
        });
        boolean seen;
        synchronized (box)
        {
            t.start();
            while (!box.ready)
            {
                box.wait();
            }
            seen = box.ready;
        }
        t.join();
        System.out.println("handshake: " + seen);
        System.out.println("MonitorStateProbe done");
    }

    interface Action
    {
        void run() throws Exception;
    }

    /** Runs {@code a} and names what it threw, with the message, or "ok". */
    static String attempt(Action a)
    {
        try
        {
            a.run();
            return "ok";
        }
        catch (Exception e)
        {
            return e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }
}
