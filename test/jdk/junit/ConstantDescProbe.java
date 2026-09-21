/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */
import java.lang.constant.ConstantDescs;

/**
 * Are {@code java.lang.constant}'s statics actually SET on metal?
 *
 * <p>The MethodHandle arc's blocker is a null {@code ConstantDescs.BSM_PRIMITIVE_CLASS}, which was diagnosed
 * as a {@code <clinit>} ORDERING inversion. {@link ClinitOrderProbe} reduces that ordering shape to twenty
 * lines and PASSES on metal, so the diagnosis is incomplete -- reproducing the shape is not reproducing the
 * condition. This asks the other question: both classes are in the writer's BAKE DOMAIN
 * ({@code java/}/{@code jdk/}/{@code sun/}), where statics are snapshotted from the seed JVM rather than
 * produced by running an initializer here.
 *
 * <p>Null-ness is printed rather than the values: rendering a {@code DirectMethodHandleDesc} would call
 * {@code toString} and pull more of the denied MethodHandle surface, which is not what is being asked.
 *
 * <p>Fields are read in DECLARATION ORDER (CD_Class line 79, BSM_PRIMITIVE_CLASS line 199, CD_int line 250)
 * because that order is the whole point of the ordering hypothesis this is testing.
 */
public class ConstantDescProbe
{
    public static void main(String[] args)
    {
        System.out.println("CD_Class null       = " + (ConstantDescs.CD_Class == null) + " (want false)");
        System.out.println("BSM_PRIMITIVE null  = " + (ConstantDescs.BSM_PRIMITIVE_CLASS == null) + " (want false)");
        System.out.println("CD_int null         = " + (ConstantDescs.CD_int == null) + " (want false)");
        System.out.println("ConstantDescProbe done");
    }
}
