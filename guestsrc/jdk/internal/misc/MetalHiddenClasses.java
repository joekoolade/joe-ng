/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */
package jdk.internal.misc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Classes defined from bytes at run time through {@code JavaLangAccess.defineClass}: the VM side of
 * {@code Lookup.defineHiddenClass}, which java.lang.invoke uses to spin every LambdaForm class its pregenerated
 * Holder classes do not cover.
 *
 * <p><b>A hidden class is renamed before it is defined.</b> The VM's class registry is keyed by NAME, and every
 * class InvokerBytecodeGenerator spins of one kind is called the same thing ({@code java/lang/invoke/LambdaForm$MH}),
 * so a second definition would collide with the first -- and every self-reference in it (its own statics, the
 * {@code ldc} of its own class its initializer does) would resolve to the wrong class. HotSpot appends a suffix
 * to a hidden class's name; this does the same in the bytes, by rewriting the Utf8 that {@code this_class} names.
 * Every self-reference goes through that one Class entry, so the rewrite is consistent by construction.
 *
 * <p><b>Class data.</b> Stock keeps a hidden class's class data in a field of its mirror; here it is a map,
 * filled BEFORE the class initializes, because a spun class's {@code <clinit>} is exactly
 * {@code MethodHandles.classData(ThisClass)}. Classes are never unloaded, so nothing is ever removed.
 */
public final class MetalHiddenClasses
{
    /** The flag Lookup.ClassOption.HIDDEN_CLASS sets (MethodHandleNatives.Constants.HIDDEN_CLASS). */
    public static final int HIDDEN_CLASS = 0x00000002;

    private static final ConcurrentHashMap<Class<?>, Object> DATA = new ConcurrentHashMap<>();
    private static final Set<Class<?>> HIDDEN = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SEQ = new AtomicLong();

    private MetalHiddenClasses()
    {
    }

    /**
     * Define {@code b} -- as a hidden class (renamed, recorded, its class data stored) when {@code flags} says
     * so -- and initialize it if asked. The class data is in place before any initializer can run.
     */
    public static Class<?> define(byte[] b, int flags, Object classData, boolean initialize)
    {
        boolean hidden = (flags & HIDDEN_CLASS) != 0;
        byte[] bytes = hidden ? renamed(b, "+" + SEQ.incrementAndGet()) : b;
        Class<?> c = defineClass0(null, bytes, 0, bytes.length);
        if (c == null)
        {
            throw new ClassFormatError("joe-ng: the VM refused a class defined at run time");
        }
        if (hidden)
        {
            HIDDEN.add(c);
        }
        if (classData != null)
        {
            DATA.put(c, classData);
        }
        if (initialize)
        {
            Unsafe.getUnsafe().ensureClassInitialized(c);
        }
        return c;
    }

    /** {@code JavaLangAccess.classData}: what {@link #define} was given for {@code c}, or null. */
    public static Object classData(Class<?> c)
    {
        return DATA.get(c);
    }

    /** {@code Class.isHidden}: whether {@code c} was defined here as a hidden class. */
    public static boolean isHidden(Class<?> c)
    {
        return HIDDEN.contains(c);
    }

    /**
     * {@code b} with its own class renamed to the old name plus {@code suffix}: the constant pool is walked to
     * find the Utf8 that {@code this_class} names, and that one entry is replaced. Nothing after the constant
     * pool holds a byte offset into it, so shifting the rest of the file is safe.
     */
    static byte[] renamed(byte[] b, String suffix)
    {
        int count = u2(b, 8);
        int[] start = new int[count];
        int p = 10;
        int i = 1;
        while (i < count)
        {
            start[i] = p;
            int tag = b[p] & 0xFF;
            if (tag == 1)
            {
                p += 3 + u2(b, p + 1);
            }
            else if (tag == 5 || tag == 6)
            {
                p += 9;
                i += 1;                                 // a long or double takes two slots (JVMS 4.4.5)
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20)
            {
                p += 3;
            }
            else if (tag == 15)
            {
                p += 4;
            }
            else if (tag == 3 || tag == 4 || tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17 || tag == 18)
            {
                p += 5;
            }
            else
            {
                throw new ClassFormatError("joe-ng: constant pool tag " + tag);
            }
            i += 1;
        }
        int thisClass = u2(b, p + 2);                   // after the pool: access_flags, then this_class
        int utf = start[u2(b, start[thisClass] + 1)];
        int len = u2(b, utf + 1);
        byte[] add = suffix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[b.length + add.length];
        System.arraycopy(b, 0, out, 0, utf + 3 + len);
        System.arraycopy(add, 0, out, utf + 3 + len, add.length);
        System.arraycopy(b, utf + 3 + len, out, utf + 3 + len + add.length, b.length - (utf + 3 + len));
        int nlen = len + add.length;
        out[utf + 1] = (byte) (nlen >> 8);
        out[utf + 2] = (byte) nlen;
        return out;
    }

    private static int u2(byte[] b, int p)
    {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.defineClass}): define the bytes, answer the mirror. */
    private static native Class<?> defineClass0(String name, byte[] b, int off, int len);
}
