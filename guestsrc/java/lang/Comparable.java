/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-28
 */
package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/Comparable<T>}: generic, exactly like the real one, so that a class
 * implementing {@code Comparable<Self>} with a {@code compareTo(Self)} makes javac synthesise a
 * {@code compareTo(Object)} BRIDGE method (checkcast + invokevirtual the typed one). That bridge is how
 * {@code invokeinterface Comparable.compareTo(Object)} (e.g. from a generic {@code Collections.sort}) reaches
 * the element's typed {@code compareTo} -- the first bridge-method dispatch the loader exercises.
 */
public interface Comparable<T>
{
    int compareTo(T o);
}
