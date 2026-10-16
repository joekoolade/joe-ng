/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;

/**
 * A class-initialization ORDER case. {@code Class.describeConstable} on an array of a primitive is the first
 * touch of the {@code java.lang.constant} family here, and stock's initializers are written for JVMS order:
 * {@code ConstantDescs} assigns {@code BSM_PRIMITIVE_CLASS} before it reads anything of
 * {@code PrimitiveClassDescImpl}, whose own initializer then builds descriptors that need that bootstrap. Run in
 * any other order, a descriptor's {@code requireNonNull} meets the unset field. Then descriptors of every shape,
 * read back through their own API. Prints values only; the host diff is the gate.
 */
public class ConstantDescOrderProbe
{
    public static void main(String[] args)
    {
        System.out.println("int[] " + int[].class.describeConstable().map(Object::toString).orElse("empty"));
        System.out.println("String " + String.class.describeConstable().map(Object::toString).orElse("empty"));
        System.out.println("long " + long.class.describeConstable().map(ClassDesc::descriptorString).orElse("empty"));
        System.out.println("String[][] " + String[][].class.describeConstable().map(ClassDesc::descriptorString)
                .orElse("empty"));
        System.out.println("CD_int " + ConstantDescs.CD_int.descriptorString() + " primitive="
                + ConstantDescs.CD_int.isPrimitive() + " display=" + ConstantDescs.CD_int.displayName());
        System.out.println("BSM_PRIMITIVE_CLASS null? " + (ConstantDescs.BSM_PRIMITIVE_CLASS == null));
        System.out.println("CD_int[] " + ConstantDescs.CD_int.arrayType().descriptorString());
        System.out.println("ConstantDescOrderProbe done");
    }
}
