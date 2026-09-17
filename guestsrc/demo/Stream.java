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
package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A mini eager stream over a {@link List}: {@code filter}/{@code map} return a fresh {@code Stream} of the
 * transformed elements, {@code forEach} runs a side-effect. Each stage takes a functional interface -- so a
 * chained pipeline drives {@code Predicate.test}/{@code Function.apply}/{@code Consumer.accept} through
 * invokeinterface into the caller's lambdas, all over the List/Iterator machinery. Not the real
 * {@code java.util.stream.Stream} (lazy, huge) -- just enough to compose the pieces into a real pipeline.
 */
public final class Stream
{
    private final List elements;

    private Stream(List elements)
    {
        this.elements = elements;
    }

    public static Stream of(List src)
    {
        return new Stream(src);
    }

    public Stream filter(Predicate p)
    {
        List out = new ArrayList();
        for (Object o : elements)
        {
            if (p.test(o))
            {
                out.add(o);
            }
        }
        return new Stream(out);
    }

    public Stream map(Function f)
    {
        List out = new ArrayList();
        for (Object o : elements)
        {
            out.add(f.apply(o));
        }
        return new Stream(out);
    }

    public void forEach(Consumer c)
    {
        for (Object o : elements)
        {
            c.accept(o);
        }
    }

    /** Fold to a single value: {@code acc = op.apply(acc, element)} from {@code identity}. A terminal op. */
    public Object reduce(Object identity, BinaryOperator op)
    {
        Object acc = identity;
        for (Object o : elements)
        {
            acc = op.apply(acc, o);
        }
        return acc;
    }

    /** Materialise the pipeline into a fresh {@link List} -- a terminal that re-collects into a collection. */
    public List toList()
    {
        List out = new ArrayList();
        for (Object o : elements)
        {
            out.add(o);
        }
        return out;
    }
}
