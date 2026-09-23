/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-23
 */

/*
 * One runner for every stock java/math jtreg `@run main` test joe-ng can host, so a single image and a
 * single boot covers all of them. That is not tidiness: a program that touches BigDecimal demand-loads
 * BigInteger, MathContext, RoundingMode, ForkJoinPool and the whole ThreadContainer family, and load time
 * is super-linear here -- paying that closure once instead of once per test is the difference between a
 * run that finishes and six that do not. The zip suite is wired the same way for the same reason.
 *
 * Each test class is UNMODIFIED, copied byte-for-byte from the OpenJDK tree. jtreg's `@run main` contract
 * is "main() returns normally", and every one of these signals failure by throwing -- so this runner is the
 * whole engine those tests need. A caught Throwable is reported with its class and message rather than a
 * bare count, because on this VM a failure is as likely to be a missing java.base member as a wrong answer,
 * and the two read completely differently.
 *
 * TWO OF THE NINE COMPILABLE `@run main` TESTS ARE NOT HOSTABLE HERE, AND THAT IS A SCOPE BOUNDARY RATHER
 * THAN A GAP -- both were run first and removed on what they DID, not on a reading of their names:
 *
 *   BigInteger/ModPowPowersof2     EXECS A SECOND JVM. Its body builds a `bin/java` command line and calls
 *                                  Runtime.getRuntime().exec to run its own nested class in a child
 *                                  process. There is no OS beneath this VM, so no process can be created;
 *                                  it died with an NPE eight frames deep in ProcessBuilder.start. Nothing
 *                                  about java.math is exercised before that point.
 *   BigInteger/ExtremeShiftingTests  NEEDS HALF A GIGABYTE, by its own jtreg tag (`-Xmx512m`). It does
 *                                  ONE.shiftLeft(Integer.MIN_VALUE), i.e. a magnitude 2^31 bits = 256 MiB
 *                                  wide. The boot ended in `large region OOM`, which is the allocator
 *                                  correctly refusing rather than a defect.
 *
 * The remaining four are the java/math tests this VM can actually host, and they run the real arithmetic.
 */
public class MathJtregAll
{
    private static int ran;
    private static int failures;

    private interface Body
    {
        void run() throws Throwable;
    }

    private static void test(String name, Body b)
    {
        ran += 1;
        try
        {
            b.run();
            System.out.println("  ok   " + name);
        }
        catch (Throwable t)
        {
            failures += 1;
            String msg = t.getMessage();
            System.out.println("  FAIL " + name + " -> " + t.getClass().getName()
                               + (msg == null ? "" : ": " + msg));
            t.printStackTrace();                         // the CLASS alone cannot say where; this can
        }
    }

    public static void main(String[] args) throws Exception
    {
        String[] none = new String[0];
        test("BigDecimal/ToPlainStringTests", () -> ToPlainStringTests.main(none));
        test("BigDecimal/StrippingZerosTest", () -> StrippingZerosTest.main(none));
        test("BigDecimal/RangeTests", () -> RangeTests.main(none));
        test("BigDecimal/FloatDoubleValueTests", () -> FloatDoubleValueTests.main(none));

        System.out.println("math jtreg: ran " + ran + ", failures " + failures);
        System.out.println(failures == 0 ? "ALL PASSED" : "SOME FAILED");
    }
}
