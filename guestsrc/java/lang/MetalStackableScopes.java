/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
package java.lang;

import jdk.internal.vm.StackableScope;

/**
 * The bridge from {@code MetalJavaLangAccess} to {@link Thread}'s package-private head-of-scope-stack accessors, as
 * {@link MetalCarrierLocals} is for carrier thread-locals: stock's JavaLangAccess is an anonymous class inside
 * {@code System} and calls them directly. Reached by every {@code ScopedValue.where(...).call/run}, through
 * {@code ScopedValueContainer} -- e.g. {@code Charset.forName} of a name no built-in charset knows.
 */
public final class MetalStackableScopes
{
    private MetalStackableScopes()
    {
    }

    public static StackableScope head(Thread thread)
    {
        return thread.headStackableScopes();
    }

    public static void setHead(StackableScope scope)
    {
        Thread.setHeadStackableScope(scope);
    }
}
