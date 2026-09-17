/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-11
 */
package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reached ONLY reflectively, so RTA never walks this body: the constructor reference below is therefore
 * compiled LATE, and nothing has pulled {@link CtorRefLateTarget}. That is the condition the ordinary
 * ctor-reference demo cannot create -- there the target is in the batch, so the thunk bakes a valid TIB.
 */
public final class CtorRefLate
{
    public static String make()
    {
        Function<List<String>, CtorRefLateTarget> f = CtorRefLateTarget::new;
        Object o = f.apply(new ArrayList<String>());
        // The cast is the operation that failed in the launcher: a null-TIB object satisfies nothing.
        CtorRefLateTarget t = (CtorRefLateTarget) o;
        return t.tag();
    }
}
