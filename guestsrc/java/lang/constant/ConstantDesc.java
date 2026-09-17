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
 * Bare-metal stub of {@code java.lang.constant.ConstantDesc} — see {@link Constable}. Empty marker so stock
 * value classes that declare {@code implements ConstantDesc} do not drag the whole {@code java.lang.constant}
 * package into a demand-load closure. Its nominal-descriptor methods are never reached on metal.
 */
public interface ConstantDesc
{
}
