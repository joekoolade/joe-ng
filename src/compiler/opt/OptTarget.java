/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package compiler.opt;

/**
 * What the optimizing tier needs from a target (PLAN.md "Targets": AArch64 first, built for x86-64 and PowerPC).
 * The IR, its optimizations, liveness, allocation and the frame's slot assignment are target-neutral; a target
 * supplies its register file, its frame conventions and -- from O1-2b -- instruction selection into the machine
 * IR and emission through its own encoder.
 */
public abstract class OptTarget
{
    public abstract String name();

    public abstract RegFile regs();

    /** Bytes in the target's word: what {@link Kind#W} and a reference are. */
    public abstract int wordBytes();

    /** Whether a call leaves the return address on the stack (x86) rather than in a link register (AArch64,
     *  PowerPC). */
    public abstract boolean returnAddressOnStack();

    /** Stack-pointer alignment at a call, in bytes. */
    public abstract int stackAlign();
}
