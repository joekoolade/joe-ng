/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-21
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.lang.reflect.Method;

/**
 * Default-method selection is the MAXIMALLY SPECIFIC declaring interface (JVMS 5.4.3.3), whatever order the
 * interfaces are listed in and whatever ran first. Each receiver is reached REFLECTIVELY before any direct call,
 * because the loader used to fill an itable slot with the first default body that happened to be REGISTERED in
 * closure order: VirtualInvokeProbe answered {@code shape} for a {@code Polygon} override whenever something had
 * registered {@code Shape.kind} first.
 *
 * <ul>
 *   <li>{@code Square extends Base implements Polygon}, {@code Base implements Shape}: the override arrives
 *       through the subclass's own interface.</li>
 *   <li>{@code Hex extends PolyBase implements Shape}, {@code PolyBase implements Polygon}: the LESS specific
 *       interface is the one listed directly -- a first-found walk of own-then-super interfaces picks it.</li>
 *   <li>{@code Kite implements Plain, Left}, both extending {@code Shape}, only {@code Left} overriding: a
 *       diamond whose first-listed side does not override.</li>
 * </ul>
 */
public class DefaultSpecificProbe
{
    interface Shape
    {
        default String kind()
        {
            return "shape";
        }

        default String label()
        {
            return "label:" + kind();
        }
    }

    interface Polygon extends Shape
    {
        @Override
        default String kind()
        {
            return "polygon";
        }
    }

    interface Plain extends Shape
    {
    }

    interface Left extends Shape
    {
        @Override
        default String kind()
        {
            return "left";
        }
    }

    abstract static class Base implements Shape
    {
    }

    static class Square extends Base implements Polygon
    {
    }

    abstract static class PolyBase implements Polygon
    {
    }

    static class Hex extends PolyBase implements Shape
    {
    }

    static class Kite implements Plain, Left
    {
    }

    public static void main(String[] args) throws Exception
    {
        Method kind = Shape.class.getMethod("kind");
        Method label = Shape.class.getMethod("label");
        Object[] receivers = { new Square(), new Hex(), new Kite() };
        for (Object r : receivers)
        {
            String n = r.getClass().getSimpleName();
            System.out.println(n + " reflective Shape.kind = " + kind.invoke(r));
            System.out.println(n + " reflective Shape.label = " + label.invoke(r));
            System.out.println(n + " reflective own-class kind = " + r.getClass().getMethod("kind").invoke(r));
        }
        Shape sq = new Square();
        Shape hx = new Hex();
        Shape kt = new Kite();
        System.out.println("invokeinterface Shape.kind = " + sq.kind() + " " + hx.kind() + " " + kt.kind());
        System.out.println("invokevirtual kind = " + new Square().kind() + " " + new Hex().kind() + " "
                + new Kite().kind());
        System.out.println("DefaultSpecificProbe done");
    }
}
