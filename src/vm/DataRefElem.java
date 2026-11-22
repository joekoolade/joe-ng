/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */
package vm;

/**
 * The element class of {@code vm.DataRefElem[]}, the run-time class of a {@code magic.DataReferenceArray}. Never
 * instantiated: it exists so the writer can bake that array class, whose Type carries {@code
 * ObjectModel.ARRAY_DATA_ELEMS} -- the mark that tells the collector the elements are data references.
 */
final class DataRefElem
{
    private DataRefElem()
    {
    }
}
