/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

/*
 * joe-ng overlay, taken from the JDK 26 source (rule 3). HotSpot INJECTS two fields from C++ (javaClasses.hpp:
 * JVM_Method* vmtarget here, intptr_t vmindex into MemberName), which a VM with no injection has to declare.
 * Java code never reads either by name -- only the VM does, through MethodHandleNatives and the JIT's linkTo*
 * lowering -- so declaring them is the same contract. vmindex lives HERE rather than in MemberName, the one
 * deliberate difference: stock MemberName never reads its own `method` field, so a MemberName's VM state can
 * ride on the ResolvedMethodName it points at without overlaying MemberName (a field's MemberName gets one
 * too, where HotSpot leaves `method` null).
 */
final class ResolvedMethodName {
    /**
     * The METHOD-REGISTRY INDEX of the resolved method -- not its code address, which is what HotSpot stores.
     * Compiled code here dies by reachability and a registry entry's body changes when it is compiled lazily,
     * so a raw address held in a long would go stale; the index is read through to the CURRENT body at each
     * call. -1 for a field.
     */
    long vmtarget;

    /** HotSpot's MemberName.vmindex: a field's offset (the absolute static-cell address for a static), the
     *  registry index for a dispatched method, or -2 (Method::nonvirtual_vtable_index) for a direct one. */
    long vmindex;

    private Class<?> vmholder;

    ResolvedMethodName(Class<?> vmholder, long vmtarget, long vmindex) {
        this.vmholder = vmholder;
        this.vmtarget = vmtarget;
        this.vmindex = vmindex;
    }

    Class<?> declaringClass() {
        return vmholder;
    }
}
