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

import java.util.List;

/** Named only by a constructor reference inside a reflectively-reached method, so RTA never pulls it. */
public final class CtorRefLateTarget
{
    private final List<String> items;

    public CtorRefLateTarget(List<String> items)
    {
        this.items = items;
    }

    public String tag()
    {
        return "late-ctor-ref:" + items.size();
    }
}
