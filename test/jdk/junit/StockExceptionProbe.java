/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;

/**
 * The thirteen java.lang exceptions that used to be hand-written overlay shells, now STOCK. The shapes: each is
 * Serializable; IllegalThreadStateException IS an IllegalArgumentException (the shell extended RuntimeException)
 * and a catch of the supertype takes it; the (String, Throwable) constructors the shells dropped, with their cause
 * chains; IndexOutOfBounds/ArrayIndexOutOfBounds' int and long constructors and their stock messages;
 * ReflectiveOperationException as the common catch; InvocationTargetException's target and cause; NPE's message
 * (null when constructed bare, the given one otherwise, and null after an explicit fillInStackTrace -- the
 * extended-message native answers null here); and the VM's OWN implicit exceptions caught by their stock
 * supertypes. Prints values only; the host diff is the gate. The host runs with
 * {@code -XX:-ShowCodeDetailsInExceptionMessages}, the HotSpot configuration whose NPE messages this VM matches.
 */
public class StockExceptionProbe
{
    public static void main(String[] args)
    {
        Throwable[] all = {
            new ArithmeticException(), new ArrayIndexOutOfBoundsException(), new ClassNotFoundException(),
            new IllegalAccessException(), new IllegalArgumentException(), new IllegalThreadStateException(),
            new IndexOutOfBoundsException(), new InstantiationException(), new NoSuchFieldException(),
            new NoSuchMethodException(), new NullPointerException(), new ReflectiveOperationException(),
            new InvocationTargetException(null) };
        int serial = 0;
        for (int i = 0; i < all.length; i += 1)
        {
            if (all[i] instanceof Serializable)
            {
                serial += 1;
            }
        }
        System.out.println("serializable=" + serial + " of " + all.length);

        Object itse = new IllegalThreadStateException("x");
        System.out.println("ITSE instanceof IAE=" + (itse instanceof IllegalArgumentException));
        try
        {
            throw new IllegalThreadStateException("not started");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("caught as IAE: " + e.getClass().getName() + ": " + e.getMessage());
        }

        Throwable root = new IllegalStateException("root");
        IllegalArgumentException iae = new IllegalArgumentException("wrapped", root);
        System.out.println("IAE(msg,cause) msg=" + iae.getMessage() + " cause=" + iae.getCause().getMessage());
        IllegalArgumentException iae2 = new IllegalArgumentException(root);
        System.out.println("IAE(cause) msg=" + iae2.getMessage());
        System.out.println("CNFE(msg,cause) cause=" + new ClassNotFoundException("c", root).getCause().getMessage());
        System.out.println("ROE(msg,cause) cause=" + new ReflectiveOperationException("r", root).getCause().getMessage());

        System.out.println("IOOBE(int)=" + new IndexOutOfBoundsException(5).getMessage());
        System.out.println("IOOBE(long)=" + new IndexOutOfBoundsException(7L).getMessage());
        System.out.println("AIOOBE(int)=" + new ArrayIndexOutOfBoundsException(9).getMessage());

        try
        {
            throw new NoSuchMethodException("m");
        }
        catch (ReflectiveOperationException e)
        {
            System.out.println("caught as ROE: " + e.getClass().getName());
        }
        InvocationTargetException ite = new InvocationTargetException(root, "ite");
        System.out.println("ITE target=" + ite.getTargetException().getMessage() + " cause="
                + ite.getCause().getMessage() + " msg=" + ite.getMessage());

        System.out.println("NPE() msg=" + new NullPointerException().getMessage());
        System.out.println("NPE(s) msg=" + new NullPointerException("given").getMessage());
        NullPointerException filled = new NullPointerException();
        filled.fillInStackTrace();
        System.out.println("NPE after fillInStackTrace msg=" + filled.getMessage());

        int[] arr = new int[2];
        int idx = args.length + 5;
        try
        {
            arr[idx] = 1;
            System.out.println("implicit AIOOBE: not thrown");
        }
        catch (IndexOutOfBoundsException e)
        {
            System.out.println("implicit AIOOBE caught as IOOBE: " + e.getClass().getName());
        }
        int zero = args.length;
        try
        {
            System.out.println(10 / zero);
        }
        catch (RuntimeException e)
        {
            System.out.println("implicit ArithmeticException caught as RTE: " + e.getClass().getName());
        }
        String nothing = args.length == 0 ? null : "x";
        try
        {
            System.out.println(nothing.length());
        }
        catch (RuntimeException e)
        {
            System.out.println("implicit NPE caught as RTE: " + e.getClass().getName() + " serializable="
                    + (e instanceof Serializable));
        }
        System.out.println("StockExceptionProbe done");
    }
}
