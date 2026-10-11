/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-10
 */
import java.io.StringReader;
import java.nio.CharBuffer;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Stock {@code java.util.Scanner} over in-memory sources, and the {@code java.nio.CharBuffer} it reads through.
 *
 * <p>{@code Scanner} buffers its input in a heap {@code CharBuffer} ({@code CharBuffer.allocate}) and pulls text with
 * {@code Readable.read(CharBuffer)}; joe-ng denied {@code CharBuffer}, so the first token trapped. Under the default
 * locale ({@code en}) it also builds its number patterns through {@code NumberFormat}/{@code DecimalFormatSymbols}
 * (#338, #339). Each arm prints values a host JVM prints identically -- the diff is the gate.
 */
public class ScannerProbe
{
    public static void main(String[] args)
    {
        Scanner sc = new Scanner("12 34.5 true hello 1,234 -7");
        System.out.println("tokens -> " + sc.nextInt() + " " + sc.nextDouble() + " " + sc.nextBoolean() + " "
                + sc.next() + " " + sc.nextInt() + " " + sc.nextLong() + " hasNext=" + sc.hasNext());

        Scanner lines = new Scanner("first line\nsecond  line\n\nfourth");
        StringBuilder b = new StringBuilder();
        while (lines.hasNextLine())
        {
            b.append('[').append(lines.nextLine()).append(']');
        }
        System.out.println("lines -> " + b);

        Scanner csv = new Scanner("a,b,,c").useDelimiter(",");
        StringBuilder c = new StringBuilder();
        while (csv.hasNext())
        {
            c.append('<').append(csv.next()).append('>');
        }
        System.out.println("delimiter -> " + c);

        Scanner mixed = new Scanner("x 5 y 6.5 z");
        int ints = 0;
        int words = 0;
        while (mixed.hasNext())
        {
            if (mixed.hasNextInt())
            {
                ints += mixed.nextInt();
            }
            else if (mixed.hasNextDouble())
            {
                mixed.nextDouble();
                words += 100;
            }
            else
            {
                mixed.next();
                words += 1;
            }
        }
        System.out.println("hasNextInt/Double -> ints=" + ints + " words+doubles=" + words);

        Scanner find = new Scanner("key=value; other=thing");
        System.out.println("findInLine -> " + find.findInLine("o\\w+=\\w+"));

        StringBuilder big = new StringBuilder();
        int i = 0;
        while (i < 3000)
        {
            big.append(i).append(' ');
            i += 1;
        }
        Scanner reader = new Scanner(new StringReader(big.toString()));
        long sum = 0;
        while (reader.hasNextInt())
        {
            sum += reader.nextInt();
        }
        System.out.println("Reader source past one buffer: sum = " + sum + " (want 4498500)");

        try
        {
            new Scanner("abc").nextInt();
            System.out.println("mismatch: no exception (WRONG)");
        }
        catch (java.util.InputMismatchException e)
        {
            System.out.println("mismatch -> InputMismatchException");
        }
        try
        {
            new Scanner("").next();
            System.out.println("empty: no exception (WRONG)");
        }
        catch (NoSuchElementException e)
        {
            System.out.println("empty -> NoSuchElementException");
        }

        CharBuffer cb = CharBuffer.allocate(8);
        cb.put("hey").put('!');
        cb.flip();
        System.out.println("CharBuffer -> " + cb + " remaining=" + cb.remaining() + " wrap="
                + CharBuffer.wrap("abcdef", 1, 4) + " compare=" + CharBuffer.wrap("abc").compareTo(CharBuffer.wrap("abd")));
        System.out.println("ScannerProbe done");
    }
}
