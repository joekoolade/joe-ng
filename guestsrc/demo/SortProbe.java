/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-14
 */
package demo;

import java.util.Arrays;

import magic.Magic;

/** Validate the object-sort path end-to-end (Arrays.sort(Object[]) -> ComparableTimSort ->
 *  reflect/Array.newInstance temp array) on a small, fast array. */
public class SortProbe
{
    public static void main(String[] args)
    {
        Integer[] arr = { 5, 3, 8, 1, 9, 2, 7, 4, 6, 0, 5, 3 };
        Arrays.sort(arr);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < arr.length)
        {
            sb.append(arr[i].intValue());
            sb.append(' ');
            i += 1;
        }
        Magic.printStr("sorted: " + sb + "\n");   // 0 1 2 3 3 4 5 5 6 7 8 9
    }
}
