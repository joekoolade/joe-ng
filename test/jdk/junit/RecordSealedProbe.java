/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/**
 * Records and sealed classes through reflection, plus the small {@code Class} members that went with them.
 * {@code getRecordComponents}: name, type, generic type, accessor (invoked), and an annotation written on the
 * component -- for a plain, a generic and an empty record, and null for a non-record. {@code isRecord} for a
 * record, a class and an enum. {@code getPermittedSubclasses}/{@code isSealed} for a sealed class, a sealed
 * interface (with a record and a class implementing it), a non-sealed subclass and an unsealed class. Then
 * {@code componentType}, {@code forPrimitiveName}, {@code isHidden} and {@code getSigners}. Prints values only; the host diff is the gate.
 */
public class RecordSealedProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.RECORD_COMPONENT, ElementType.FIELD })
    @interface Unit
    {
        String value();
    }

    record Point(int x, @Unit("m") double y)
    {
    }

    record Box<T>(T item, List<String> tags)
    {
    }

    record Empty()
    {
    }

    sealed interface Shape permits Circle, Square
    {
    }

    record Circle(double r) implements Shape
    {
    }

    static final class Square implements Shape
    {
    }

    abstract static sealed class Animal permits Dog, Cat
    {
    }

    static final class Dog extends Animal
    {
    }

    static non-sealed class Cat extends Animal
    {
    }

    enum Color
    {
        RED
    }

    static void components(Class<?> c) throws Exception
    {
        RecordComponent[] rcs = c.getRecordComponents();
        if (rcs == null)
        {
            System.out.println(c.getSimpleName() + " components null");
            return;
        }
        System.out.println(c.getSimpleName() + " components " + rcs.length);
        for (RecordComponent rc : rcs)
        {
            Unit u = rc.getAnnotation(Unit.class);
            System.out.println("  " + rc.getName() + " type=" + rc.getType().getName() + " generic="
                    + rc.getGenericType().getTypeName() + " accessor=" + rc.getAccessor().getName() + " declaringRecord="
                    + rc.getDeclaringRecord().getSimpleName() + " unit=" + (u == null ? "null" : u.value())
                    + " toString=" + rc);
        }
    }

    static String names(Class<?>[] cs)
    {
        if (cs == null)
        {
            return "null";
        }
        String[] n = new String[cs.length];
        for (int i = 0; i < cs.length; i++)
        {
            n[i] = cs[i].getSimpleName();
        }
        Arrays.sort(n);
        return Arrays.toString(n);
    }

    public static void main(String[] args) throws Exception
    {
        components(Point.class);
        components(Box.class);
        components(Empty.class);
        components(Circle.class);
        components(Square.class);
        Point p = new Point(3, 4.5);
        RecordComponent[] prc = Point.class.getRecordComponents();
        System.out.println("accessor values " + prc[0].getAccessor().invoke(p) + " " + prc[1].getAccessor().invoke(p));
        System.out.println("isRecord Point=" + Point.class.isRecord() + " Square=" + Square.class.isRecord() + " Color="
                + Color.class.isRecord() + " Record=" + Record.class.isRecord());

        System.out.println("sealed Shape=" + Shape.class.isSealed() + " permits " + names(Shape.class.getPermittedSubclasses()));
        System.out.println("sealed Animal=" + Animal.class.isSealed() + " permits "
                + names(Animal.class.getPermittedSubclasses()));
        System.out.println("sealed Cat=" + Cat.class.isSealed() + " permits " + names(Cat.class.getPermittedSubclasses()));
        System.out.println("sealed Dog=" + Dog.class.isSealed() + " permits " + names(Dog.class.getPermittedSubclasses()));
        System.out.println("sealed String=" + String.class.isSealed() + " int[]=" + int[].class.isSealed());

        System.out.println("componentType int[]=" + int[].class.componentType() + " String[][]="
                + String[][].class.componentType() + " String=" + String.class.componentType());
        System.out.println("forPrimitiveName int=" + Class.forPrimitiveName("int") + " void="
                + Class.forPrimitiveName("void") + " Integer=" + Class.forPrimitiveName("java.lang.Integer"));
        System.out.println("isHidden Point=" + Point.class.isHidden() + " int=" + int.class.isHidden());
        System.out.println("getSigners Point=" + Point.class.getSigners() + " int=" + int.class.getSigners());
        System.out.println("RecordSealedProbe done");
    }
}
