/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-20
 */
package magic;

/**
 * Marker: a class whose code gets NO yieldpoints (frames/GC arc S3). Baseline polls the yieldpoint request word in
 * every framed method's prologue and at every loop header; a class that implements this gets neither, so a task
 * running its code never stops there for a switch or a collection.
 *
 * <p>It is for code that runs the switch or the collection itself (the scheduler, the collector, the allocator),
 * where a poll would re-enter what it belongs to, and for code that runs with IRQs masked (the interrupt path, the
 * console driver), where a poll can never switch. A poll elsewhere is safe without it: the slow path does not
 * yield while IRQs are masked, so it switches only where the timer could already have preempted. MEASURED on the
 * Pi: the console's TX drain spins under its ring lock with IRQs masked, and while output backed up its polls
 * took the slow path 1.8 million times in one boot, every one of them a call that could not switch.
 *
 * <p>Jikes RVM's {@code Uninterruptible} pragma, in its early form: a marker interface on the class, read from the
 * class's own interface list by the compiler in both worlds (the writer and the on-metal JIT).
 */
public interface Uninterruptible
{
}
