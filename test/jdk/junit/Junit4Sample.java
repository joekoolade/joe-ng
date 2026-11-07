/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-07
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * A JUnit 4 class, run through the VINTAGE engine. The engine was denylisted until 2026-11-07, so this is the
 * first JUnit 4 code the metal runs. The shapes are the ones JUnit 4 handles differently from Jupiter: a
 * {@code @Before} that must run on a FRESH instance per test, {@code @Test(expected=...)} (the engine catches
 * the throw and passes the test), an {@code @Ignore}d test that must be reported as skipped, and a real
 * failure that must be reported rather than crash the run. Expected: 5 tests found, 3 successful, 1 failed,
 * 1 skipped.
 */
public class Junit4Sample
{
    private int counter;

    @Before
    public void setUp()
    {
        counter = 41;
    }

    @Test
    public void arithmetic()
    {
        assertEquals(42, counter + 1);
    }

    @Test
    public void freshInstancePerTest()
    {
        counter += 1;
        assertEquals(42, counter);
    }

    @Test(expected = ArithmeticException.class)
    public void expectedException()
    {
        int zero = counter - 41;
        assertTrue(42 / zero > 0);
    }

    @Ignore("skipped on purpose")
    @Test
    public void ignored()
    {
        throw new RuntimeException("an @Ignore'd test must never run");
    }

    @Test
    public void deliberateFailure()
    {
        assertEquals(1, 2);
    }
}
