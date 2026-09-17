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
 * M8 Stage 3 (reified loader, first step): one entry of the phase-A dynamic-linking table -- a method's
 * offset cell keyed by its class+name+descriptor. Replaces the five parallel {@code dl*} static arrays in
 * {@link Loader} with a small reified object, the first move from the loader's flat-static registries toward
 * the JikesRVM-style RVMClass/RVMMethod model. JDK-free (only primitive fields) so it compiles into the image
 * by our own baseline compiler and the loader can allocate it while it runs, on the metal.
 */
final class DynLink
{
    long blob;      // the method's class blob base (holds its Utf8 strings)
    int  classOff;  // class name Utf8 offset (in blob)
    int  nameOff;   // method name Utf8 offset (in blob)
    int  descOff;   // descriptor Utf8 offset (in blob)
    long cell;      // its offset cell: holds the lazy stub, then the compiled buffer after first call
}
