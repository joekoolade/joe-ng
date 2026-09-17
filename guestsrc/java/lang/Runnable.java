/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-25
 */
package java.lang;

/**
 * joe-ng's JDK-free reimplementation of {@link java.lang.Runnable}, compiled into the boot image as a
 * raw blob (via {@code --patch-module java.base}) and loaded on the metal by {@code vm/Loader} when the
 * demand-loaded {@code demo/DiningPhilosophers} references it. Only what the demo needs — one method.
 */
public interface Runnable
{
    void run();
}
