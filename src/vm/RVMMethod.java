/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-17
 */
package vm;

/**
 * M8 Stage 3 (reified loader): one entry of the global method registry -- a compiled method known to the
 * loader, keyed by declaring class + name + descriptor, with its compiled buffer and stack-trace debug info.
 * Reifies the eight parallel {@code rg*} static arrays in {@link Loader} into one object -- the first of the
 * named JikesRVM types (an RVMMethod). JDK-free (primitive fields only) so it compiles into the image and the
 * loader allocates it on the metal; {@code Loader.rgTab} is a GC root.
 */
final class RVMMethod
{
    long base;      // declaring class blob base (holds its Utf8 strings)
    int  classOff;  // class name Utf8 offset
    int  nameOff;   // method name Utf8 offset
    int  descOff;   // descriptor Utf8 offset
    long buf;       // compiled buffer address
    long line;      // {u32 count,(u32 wordOff,u32 line)*} table address, or 0
    long src;       // this class's SourceFile filename Utf8 address, or 0
    int  access;    // access_flags (ACC_STATIC etc.) -- reflective invoke + getModifiers
}
