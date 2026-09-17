/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-29
 */
package java.lang.constant;

/**
 * Bare-metal stub of {@code java.lang.constant.Constable}. The constant/condy nominal-descriptor API is unused
 * on metal, but stock value classes (Integer/Long/Float/Double/String/...) declare {@code implements Constable,
 * ConstantDesc}. Structurally pulling the real interfaces drags the ENTIRE {@code java.lang.constant} package
 * (ClassDesc, MethodTypeDesc, DynamicConstantDesc, ConstantDescs, DirectMethodHandleDesc, ...) plus the
 * MethodHandle machinery into a demand-load closure. An empty marker interface (guest override wins via
 * addIfAbsent) stops that cascade at the root: {@code describeConstable()} is never reached on metal, so the
 * missing method is harmless.
 */
public interface Constable
{
}
