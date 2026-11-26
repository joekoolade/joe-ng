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
package magic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pragma: compile this method with the optimizing tier (PLAN.md "The optimizing compiler"), with no writer flag.
 * CLASS retention: the writer reads it from the method's {@code RuntimeInvisibleAnnotations}, so nothing about it
 * exists at run time. A method the tier does not accept yet still falls back to Baseline, and is counted.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Optimize
{
}
