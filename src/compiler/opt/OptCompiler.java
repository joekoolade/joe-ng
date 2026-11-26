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
 * The optimizing tier's front door (PLAN.md "The optimizing compiler"). O1-1: build a method's SSA graph and
 * verify it; nothing is emitted yet. The result says which of three things happened -- built and verified, not
 * accepted (a shape the tier does not cover: {@link #reason}), or built but malformed ({@link #error}: a builder
 * bug).
 */
public final class OptCompiler
{
    public static final int ACCEPTED = 0;
    public static final int REJECTED = 1;
    public static final int MALFORMED = 2;

    private Graph graph;
    private int reason;
    private int reasonBci;
    private String error;

    public int compile(byte[] cls, int[] cpOff, int[] cpTag, byte[] code, int descOff, boolean isStatic,
                       boolean isSynchronized, int maxLocals, int[] exStart, int[] exEnd, int[] exHandler,
                       int[] exCatch, int exCount)
    {
        SsaBuilder b = new SsaBuilder(cls, cpOff, cpTag, code, descOff, isStatic, isSynchronized, maxLocals,
                                      exStart, exEnd, exHandler, exCatch, exCount);
        graph = b.build();
        if (graph == null)
        {
            reason = b.reason();
            reasonBci = b.reasonBci();
            return REJECTED;
        }
        error = Verifier.verify(graph);
        if (error != null)
        {
            return MALFORMED;
        }
        return ACCEPTED;
    }

    public Graph graph()
    {
        return graph;
    }

    public int reason()
    {
        return reason;
    }

    public int reasonBci()
    {
        return reasonBci;
    }

    public String error()
    {
        return error;
    }
}
