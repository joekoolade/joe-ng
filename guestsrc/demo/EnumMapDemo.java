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

import java.util.EnumMap;

/**
 * Reduction of the failure the zip JUnit run hit with the harness seed removed: an
 * {@code ArrayIndexOutOfBoundsException} at {@code EnumMap.getKeyUniverse}, whose whole body is
 * {@code SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType)} — a single invokeinterface.
 * AIOOBE (rather than the directory-miss NPE) means the itable for JavaLangAccess WAS found on the receiver
 * and the SLOT's entry is empty or wrong.
 */
public class EnumMapDemo
{
    enum Color { RED, GREEN, BLUE }

    public static void main(String[] args)
    {
        EnumMap<Color, String> m = new EnumMap<>(Color.class);
        m.put(Color.RED, "r");
        m.put(Color.BLUE, "b");
        System.out.println("enummap size=" + m.size() + " red=" + m.get(Color.RED) + " blue=" + m.get(Color.BLUE));
    }
}
