/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-27
 */
package demo;

/** Implements both, but nothing statically reachable calls {@link #pruned()}. */
public class RtaSpeaker implements RtaSpeak
{
    public String reached()
    {
        return "reached";
    }

    public String pruned()
    {
        return "pruned";
    }
}
