/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import magic.Address;
import magic.DataReference;
import magic.AddressArray;
import magic.Magic;
import objectmodel.ObjectModel;

/**
 * The primitive types: their Type nodes and {@code Class} mirrors ({@code int.class == Integer.TYPE}), their
 * names, descriptor characters and {@code newarray} atypes (JikesRVM: {@code Primitive}). Split out of
 * {@link Loader}.
 */
final class Primitives
{
    private Primitives()
    {
    }

    private static AddressArray primTypeCache;                 // primitive Type per atype-style index (see primTypeIdx)

    /** Index 0..8 for a JVMS primitive descriptor char (Z C F D B S I J V), or -1. */
    private static int primTypeIdx(int c)
    {
        if (c == 0x5A) { return 0; }                    // 'Z' boolean
        if (c == 0x43) { return 1; }                    // 'C' char
        if (c == 0x46) { return 2; }                    // 'F' float
        if (c == 0x44) { return 3; }                    // 'D' double
        if (c == 0x42) { return 4; }                    // 'B' byte
        if (c == 0x53) { return 5; }                    // 'S' short
        if (c == 0x49) { return 6; }                    // 'I' int
        if (c == 0x4A) { return 7; }                    // 'J' long
        if (c == 0x56) { return 8; }                    // 'V' void
        return -1;
    }

    /**
     * The Type node backing {@code int.class} and friends, created on demand and cached. It is a real heap
     * node rather than a small sentinel in the mirror deliberately: every {@code Class} native dereferences
     * the mirror's Type word, so a tiny value there would have to be guarded at each of them.
     */
    static Address primitiveType(int descChar)
    {
        int idx = primTypeIdx(descChar);
        if (idx < 0)
        {
            return Address.zero();
        }
        if (primTypeCache == null)
        {
            primTypeCache = AddressArray.create(9);
        }
        if (primTypeCache.get(idx).isZero())
        {
            DataReference type = Heap.allocType();
            type.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).store(
                    ObjectModel.PRIM_TYPE_TAG | (long) descChar);
            type.plus(ObjectModel.TYPE_SUPER_OFFSET).store(0L);         // no super: instanceof stops here
            type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).store(0L);
            primTypeCache.set(idx, type.toAddress());
        }
        return primTypeCache.get(idx);
    }

    /** The {@code Class} mirror for a primitive, e.g. {@code int.class}. Identity-stable via the mirror cache. */
    static long primitiveMirror(int descChar)
    {
        Address t = primitiveType(descChar);
        return t.isZero() ? 0L : ClassMirrors.classMirror(t);
    }

    /** True if {@code type} is a primitive Type ({@link ObjectModel#PRIM_TYPE_TAG}). */
    static boolean isPrimitiveType(Address type)
    {
        if (type.isZero())
        {
            return false;
        }
        long instSize = type.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        return (instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.PRIM_TYPE_TAG;
    }

    /** The descriptor char of a primitive Type ('I'), or 0. */
    static int primTypeChar(Address type)
    {
        if (!isPrimitiveType(type))
        {
            return 0;
        }
        return (int) (type.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong() & 0xFFL);
    }

    /**
     * Install {@code Integer.TYPE} and friends. The writer cannot bake these: the seed JVM's value is a HOST
     * {@code java.lang.Class}, which has no image representation, so the snapshot stores 0 and {@code int.class}
     * -- which javac compiles to {@code getstatic Integer.TYPE}, not to an {@code ldc} -- reads null. Seeded
     * here for the same reason {@code System.out} and the Integer cache are. No-op for classes not in the batch.
     */
    static void seedPrimitiveTypes()
    {
        if (BootSeeds.sdPrim)
        {
            return;
        }
        // LATCHED PER WRAPPER, not all-or-nine. A wrapper not yet registered has no TYPE slot to write and
        // must be retried -- but an all-or-nothing flag would then keep re-scanning for the EIGHT that are
        // already seeded, and a wrapper a closure never carries (java/lang/Void is the likely one) would
        // hold the flag down for the whole launch and preserve most of the cost this fix exists to remove.
        seedPrimType(Magic.bytes("java/lang/Integer"), 0x49, 0);      // 'I'
        seedPrimType(Magic.bytes("java/lang/Long"), 0x4A, 1);         // 'J'
        seedPrimType(Magic.bytes("java/lang/Double"), 0x44, 2);       // 'D'
        seedPrimType(Magic.bytes("java/lang/Float"), 0x46, 3);        // 'F'
        seedPrimType(Magic.bytes("java/lang/Short"), 0x53, 4);        // 'S'
        seedPrimType(Magic.bytes("java/lang/Byte"), 0x42, 5);         // 'B'
        seedPrimType(Magic.bytes("java/lang/Character"), 0x43, 6);    // 'C'
        seedPrimType(Magic.bytes("java/lang/Boolean"), 0x5A, 7);      // 'Z'
        seedPrimType(Magic.bytes("java/lang/Void"), 0x56, 8);         // 'V'
        BootSeeds.sdPrim = BootSeeds.sdPrimMask == 0x1FF;                                 // all nine landed: skip the block outright
    }

    /** Seed one wrapper's {@code TYPE} unless {@code bit} says it already landed; records it in
     *  {@link BootSeeds#sdPrimMask} when it does. */
    private static void seedPrimType(byte[] cls, int descChar, int bit)
    {
        if ((BootSeeds.sdPrimMask & (1 << bit)) != 0)
        {
            return;
        }
        long slot = Statics.staticSlotOf(cls, Magic.bytes("TYPE"));
        if (slot == 0L)
        {
            return;                                     // the wrapper is not registered yet -- retry next batch
        }
        long m = primitiveMirror(descChar);
        Address.fromLong(slot).store(m);
        if (m != 0L)                                    // ... and so is a mirror the Type table cannot yet build
        {
            BootSeeds.sdPrimMask |= 1 << bit;
        }
    }

    /** The Java source name of a primitive descriptor char ("int"), as bytes. */
    private static byte[] primNameBytes(int c)
    {
        if (c == 0x5A) { return Magic.bytes("boolean"); }
        if (c == 0x43) { return Magic.bytes("char"); }
        if (c == 0x46) { return Magic.bytes("float"); }
        if (c == 0x44) { return Magic.bytes("double"); }
        if (c == 0x42) { return Magic.bytes("byte"); }
        if (c == 0x53) { return Magic.bytes("short"); }
        if (c == 0x49) { return Magic.bytes("int"); }
        if (c == 0x4A) { return Magic.bytes("long"); }
        return Magic.bytes("void");
    }

    static int primNameLen(int c)
    {
        return c == 0 ? 0 : primNameBytes(c).length;
    }

    static int writePrimName(int c, long dst, int pos)
    {
        byte[] nm = primNameBytes(c);
        int k = 0;
        while (k < nm.length)
        {
            Address.fromLong(dst).plus(pos).plus(k).store(nm[k]);
            k += 1;
        }
        return pos + nm.length;
    }

    /** A PRIMITIVE array Type's element descriptor char ('I' for {@code int[]}), or 0 for any other Type. */
    static int primArrayElemChar(Address type)
    {
        return ArrayTypes.primElemCharOf(type);
    }

    /** {@code c}'s primitive name ("int" for 'I') as raw ASCII. */
    static byte[] primName(int c)
    {
        return primNameBytes(c);
    }

    /** newarray atype (4=bool..11=long) -> element size in bytes. */
    static int primElemSize(int atype)
    {
        return atype == 4 || atype == 8 ? 1              // boolean, byte
             : atype == 5 || atype == 9 ? 2              // char, short
             : atype == 6 || atype == 10 ? 4             // float, int
             : 8;                                        // double, long
    }

    /** JVMS descriptor char for a newarray atype (4..11). */
    static int primDescChar(int atype)
    {
        if (atype == 4)  { return 0x5A; }   // 'Z'
        if (atype == 5)  { return 0x43; }   // 'C'
        if (atype == 6)  { return 0x46; }   // 'F'
        if (atype == 7)  { return 0x44; }   // 'D'
        if (atype == 8)  { return 0x42; }   // 'B'
        if (atype == 9)  { return 0x53; }   // 'S'
        if (atype == 10) { return 0x49; }   // 'I'
        return 0x4A;                        // 'J'
    }

    /** JVMS field-descriptor char -> newarray atype (Z/B/C/S/I/J/F/D), or -1 for a reference/array element. */
    static int atypeForDescChar(int c)
    {
        if (c == 0x5A) { return 4; }    // 'Z' boolean
        if (c == 0x43) { return 5; }    // 'C' char
        if (c == 0x46) { return 6; }    // 'F' float
        if (c == 0x44) { return 7; }    // 'D' double
        if (c == 0x42) { return 8; }    // 'B' byte
        if (c == 0x53) { return 9; }    // 'S' short
        if (c == 0x49) { return 10; }   // 'I' int
        if (c == 0x4A) { return 11; }   // 'J' long
        return -1;                      // 'L' reference or '[' nested array
    }
}
