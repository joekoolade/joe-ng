/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-30
 */

/*
 * One runner for the three Unsafe/Class probes, so ONE image and ONE boot covers all of them -- the same
 * reason ZipJUnitAll exists. Each probe boot pays the whole base demand-load closure before its first arm,
 * and on hardware each image is also a power cycle and a re-flash, so three images would buy three of both
 * for nothing.
 *
 * WHAT ONLY A Pi CAN SHOW, and why this image exists at all rather than the demo suite alone:
 *
 *   - `casNarrow` runs a real LDAXR/STLXR retry loop. CLAUDE.md records that an LL/SC CAS fails SPURIOUSLY on
 *     silicon -- an interrupt between the load and the store clears the exclusive monitor -- in a way it never
 *     does under emulation. That loop is what the spurious failure exists for, and no demo in the suite calls
 *     any of these members, so the suite cannot exercise it.
 *   - `Class.getComponentType`'s element char is recovered by IDENTITY against the per-atype array-TIB cache,
 *     and on a real boot an entry there may be a writer-BAKED array TIB the loader ADOPTED rather than a
 *     metal-built one. The adopted case is precisely why identity was chosen over a metal-side field.
 *   - the narrow accessors dereference an object's TIB and its Type on every write, on cold DRAM, where QEMU
 *     hands out zeroes.
 *
 * Each probe is UNMODIFIED and is invoked through its own `main`. Each is wrapped, so one probe throwing
 * cannot hide the two after it -- the shape that made a half-read log look like a clean one more than once in
 * this project. The total is printed last so there is a single number to read off the wire.
 */
public class UnsafeAll
{
    static int broken;

    static void run(String name, Runnable body)
    {
        System.out.println();
        System.out.println("===== " + name + " =====");
        try
        {
            body.run();
        }
        catch (Throwable t)
        {
            broken++;
            System.out.println("BROKEN " + name + " threw " + t.getClass().getName());
        }
    }

    public static void main(String[] args) throws Exception
    {
        run("UnsafeAccessProbe", () ->
        {
            try
            {
                UnsafeAccessProbe.main(new String[0]);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t.getClass().getName());
            }
        });
        run("UnsafeAtomicProbe", () ->
        {
            try
            {
                UnsafeAtomicProbe.main(new String[0]);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t.getClass().getName());
            }
        });
        run("ComponentTypeProbe", () ->
        {
            try
            {
                ComponentTypeProbe.main(new String[0]);
            }
            catch (Throwable t)
            {
                throw new RuntimeException(t.getClass().getName());
            }
        });

        System.out.println();
        System.out.println("UnsafeAll done, probes-broken=" + broken
            + " accessFailures=" + UnsafeAccessProbe.failures
            + " atomicFailures=" + UnsafeAtomicProbe.failures
            + " componentFailures=" + ComponentTypeProbe.failures);
    }
}
