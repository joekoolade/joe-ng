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
 * The bytecode-level state at a point: the bci and the SSA value in every local and operand-stack slot, as the
 * JVM numbers them (a long or double takes two slots, the second {@code null}). What deoptimization (O4) rebuilds
 * a Baseline frame from; until then it is built and verified so it is never retrofitted.
 */
public final class FrameState
{
    public final int bci;
    public final Instr[] locals;
    public final Instr[] stack;

    FrameState(int bci, Instr[] locals, Instr[] stack)
    {
        this.bci = bci;
        this.locals = locals;
        this.stack = stack;
    }
}
