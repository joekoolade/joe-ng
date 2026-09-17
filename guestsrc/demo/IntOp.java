/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-26
 */
package demo;

/** A functional interface whose SAM takes an argument (unlike Runnable) — exercises slice-1d lambdas. */
public interface IntOp
{
    int apply(int x);
}
