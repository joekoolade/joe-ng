/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

/**
 * Stock {@code java.nio} buffers -- HEAP and DIRECT -- across the surface the joe-ng {@code ByteBuffer} overlay never
 * had: slice/duplicate/compact, read-only views, typed views ({@code asIntBuffer}, {@code asCharBuffer}), byte order,
 * absolute and relative multi-byte access, {@code equals}/{@code compareTo}/{@code mismatch}, and bulk transfer
 * between a heap and a direct buffer.
 *
 * <p>The DIRECT arms are the adversarial ones: they put multi-byte values through {@code Unsafe} at a raw address,
 * where a put that is wider than its type corrupts the NEIGHBOURING bytes -- so every direct arm reads back the
 * neighbours too, not only the value it wrote. Prints values only, so the host diff is the gate.
 */
public class NioBufferProbe
{
    public static void main(String[] args)
    {
        for (int pass = 0; pass < 2; pass += 1)
        {
            boolean direct = pass == 1;
            String tag = direct ? "direct" : "heap";
            ByteBuffer b = direct ? ByteBuffer.allocateDirect(32) : ByteBuffer.allocate(32);
            System.out.println(tag + ": isDirect=" + b.isDirect() + " cap=" + b.capacity() + " order=" + b.order());
            b.put((byte) 0x11).putShort((short) 0x2233).putInt(0x44556677).putLong(0x8899AABBCCDDEEFFL);
            b.putInt(20, 0x01020304);
            b.put(19, (byte) 0x7E);
            b.put(24, (byte) 0x7F);
            System.out.println(tag + ": pos=" + b.position() + " getInt(20)=" + Integer.toHexString(b.getInt(20))
                    + " neighbours=" + b.get(19) + "," + b.get(24) + " getShort(1)=" + Integer.toHexString(b.getShort(1))
                    + " getLong(7)=" + Long.toHexString(b.getLong(7)));
            b.order(ByteOrder.LITTLE_ENDIAN);
            System.out.println(tag + ": LE getInt(20)=" + Integer.toHexString(b.getInt(20)) + " LE getShort(1)="
                    + Integer.toHexString(b.getShort(1)));
            b.order(ByteOrder.BIG_ENDIAN);
            b.flip();
            byte[] all = new byte[b.remaining()];
            b.duplicate().get(all);
            System.out.println(tag + ": flip remaining=" + b.remaining() + " bytes=" + Arrays.toString(all));
            ByteBuffer s = b.slice(1, 6);
            System.out.println(tag + ": slice(1,6) getShort=" + Integer.toHexString(s.getShort()) + " isDirect="
                    + s.isDirect() + " cap=" + s.capacity());
            b.position(3);
            b.compact();
            System.out.println(tag + ": compact pos=" + b.position() + " first=" + Integer.toHexString(b.get(0)));
            ByteBuffer ro = b.asReadOnlyBuffer();
            String roRes;
            try
            {
                ro.put(0, (byte) 1);
                roRes = "no exception (WRONG)";
            }
            catch (ReadOnlyBufferException e)
            {
                roRes = "ReadOnlyBufferException";
            }
            System.out.println(tag + ": readOnly put -> " + roRes + " isReadOnly=" + ro.isReadOnly());
            ByteBuffer v = direct ? ByteBuffer.allocateDirect(16) : ByteBuffer.allocate(16);
            IntBuffer ib = v.asIntBuffer();
            ib.put(7).put(-1).put(0x12345678);
            LongBuffer lb = v.asLongBuffer();
            CharBuffer cb = v.asCharBuffer();
            System.out.println(tag + ": asIntBuffer -> v.getInt(4)=" + v.getInt(4) + " v.get(8)=" + v.get(8)
                    + " asLong[0]=" + Long.toHexString(lb.get(0)) + " asChar[13]=" + (int) cb.get(7));
        }
        ByteBuffer h = ByteBuffer.allocate(8);
        ByteBuffer d = ByteBuffer.allocateDirect(8);
        h.putLong(0, 0x0102030405060708L);
        d.put(0, h, 0, 8);
        System.out.println("heap->direct copy: " + Long.toHexString(d.getLong(0)) + " equals=" + d.equals(h)
                + " compareTo=" + d.compareTo(h) + " mismatch=" + d.mismatch(h));
        d.put(7, (byte) 9);
        System.out.println("after change: equals=" + d.equals(h) + " mismatch=" + d.mismatch(h) + " compareTo>0="
                + (d.compareTo(h) > 0));
        ByteBuffer w = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}, 1, 3);
        System.out.println("wrap(1,3): pos=" + w.position() + " lim=" + w.limit() + " hasArray=" + w.hasArray()
                + " arrayOffset=" + w.arrayOffset() + " get=" + w.get());
        System.out.println("nativeOrder=" + ByteOrder.nativeOrder() + " CharBuffer.wrap=" + CharBuffer.wrap("hello", 1, 4));
        System.out.println("NioBufferProbe done");
    }
}
