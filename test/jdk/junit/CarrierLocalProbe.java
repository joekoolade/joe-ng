/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-12
 */
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jdk.internal.misc.CarrierThreadLocal;
import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.misc.Unsafe;
import sun.nio.ch.DirectBuffer;
import sun.nio.ch.Util;

/**
 * CARRIER THREAD-LOCALS: the path every socket write takes. Stock {@code NioSocketImpl.tryWrite} copies a heap
 * buffer through {@code sun.nio.ch.Util.getTemporaryDirectBuffer}, whose per-thread cache is a
 * {@code TerminatingThreadLocal} -- a {@code CarrierThreadLocal} whose get/set/remove go through
 * {@code JavaLangAccess}. NetDemo on the Pi died there ({@code getCarrierThreadLocal is not implemented}) and QEMU
 * cannot run NetDemo, so this drives the same calls without a network. Also: the exit callback a
 * {@code TerminatingThreadLocal} gets when its thread dies (how stock frees those buffers), that {@code remove}
 * cancels it, and more thread-locals on one thread than the overlay's first table holds. Prints values only;
 * the host diff is the gate (host needs --add-exports for jdk.internal.misc and sun.nio.ch).
 */
public class CarrierLocalProbe
{
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception
    {
        ByteBuffer b1 = Util.getTemporaryDirectBuffer(100);
        System.out.println("temp1 direct=" + b1.isDirect() + " cap>=100=" + (b1.capacity() >= 100) + " limit="
                + b1.limit() + " pos=" + b1.position());
        Util.releaseTemporaryDirectBuffer(b1);
        ByteBuffer b2 = Util.getTemporaryDirectBuffer(50);
        // Not b1 == b2: stock reuses the released buffer, but the cache holds IOUtil.IOV_MAX buffers and this VM
        // skips IOUtil.<clinit> (it needs jdk/internal/loader's loadLibrary), so IOV_MAX is 0 and every temporary
        // buffer is fresh. Measured, recorded in STATUS.md; correct, only slower.
        System.out.println("temp2 direct=" + b2.isDirect() + " limit=" + b2.limit());
        Util.releaseTemporaryDirectBuffer(b2);

        nativeView();

        CarrierThreadLocal<String> ctl = new CarrierThreadLocal<>();
        System.out.println("carrier initial=" + ctl.get());
        ctl.set("c");
        System.out.println("carrier set=" + ctl.get());
        ctl.remove();
        System.out.println("carrier removed=" + ctl.get());

        TerminatingThreadLocal<String> ttl = new TerminatingThreadLocal<>()
        {
            @Override
            protected String initialValue()
            {
                return "init";
            }

            @Override
            protected void threadTerminated(String value)
            {
                EVENTS.add("terminated:" + value);
            }
        };
        Thread kept = new Thread(() ->
        {
            EVENTS.add("kept get=" + ttl.get());
            ttl.set("second");
            EVENTS.add("kept get=" + ttl.get());
        });
        kept.start();
        kept.join();
        Thread removed = new Thread(() ->
        {
            ttl.set("gone");
            ttl.remove();
            EVENTS.add("removed get=" + ttl.get());
            ttl.remove();
        });
        removed.start();
        removed.join();
        Thread buffers = new Thread(() ->
        {
            ByteBuffer b = Util.getTemporaryDirectBuffer(64);
            Util.releaseTemporaryDirectBuffer(b);
            EVENTS.add("buffers thread released direct=" + b.isDirect());
        });
        buffers.start();
        buffers.join();
        for (int i = 0; i < EVENTS.size(); i += 1)
        {
            System.out.println("| " + EVENTS.get(i));
        }

        List<ThreadLocal<Integer>> many = new ArrayList<>();
        for (int i = 0; i < 40; i += 1)
        {
            int k = i;
            many.add(ThreadLocal.withInitial(() -> k * 3));
        }
        int sum = 0;
        for (int i = 0; i < many.size(); i += 1)
        {
            sum += many.get(i).get();
        }
        for (int i = 0; i < many.size(); i += 2)
        {
            many.get(i).set(1000);
        }
        int sum2 = 0;
        for (int i = 0; i < many.size(); i += 1)
        {
            sum2 += many.get(i).get();
        }
        System.out.println("many locals=" + many.size() + " sum=" + sum + " afterSet=" + sum2);
        System.out.println("CarrierLocalProbe done");
    }

    /**
     * What the socket natives SEE: stock {@code NioSocketImpl.tryWrite} puts the caller's bytes into a temporary
     * direct buffer and hands {@code SocketDispatcher.write0} its raw ADDRESS; {@code tryRead} lets {@code read0}
     * fill the address and then copies out with {@code get}. So each half is checked through the address with
     * {@code Unsafe} -- a {@code put} the native cannot see sends garbage, and a {@code get} that ignores what the
     * native stored returns nothing. (The Pi's NetDemo sent its GET and read 0 bytes.)
     */
    static void nativeView()
    {
        Unsafe u = Unsafe.getUnsafe();
        byte[] req = "GET / HTTP/1.0\r\n".getBytes();
        ByteBuffer w = Util.getTemporaryDirectBuffer(req.length);
        w.put(req, 0, req.length);
        long wa = ((DirectBuffer) w).address();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < req.length; i += 1)
        {
            seen.append((char) u.getByte(wa + i));
        }
        System.out.println("write half: native sees " + seen.toString().trim() + " match="
                + seen.toString().equals(new String(req)) + " pos=" + w.position());
        Util.releaseTemporaryDirectBuffer(w);

        ByteBuffer r = Util.getTemporaryDirectBuffer(64);
        long ra = ((DirectBuffer) r).address();
        byte[] resp = "HTTP/1.1 200 OK".getBytes();
        for (int i = 0; i < resp.length; i += 1)
        {
            u.putByte(ra + i, resp[i]);
        }
        byte[] into = new byte[64];
        r.get(into, 3, resp.length);
        System.out.println("read half: copied out " + new String(into, 3, resp.length) + " pos=" + r.position());
        Util.releaseTemporaryDirectBuffer(r);
    }
}
