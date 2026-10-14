/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.util.List;
import java.util.stream.Collectors;

/**
 * A stack frame inside an INTERFACE DEFAULT METHOD, the shape System.Logger.log has: SimpleConsoleLogger skips
 * its own machinery with {@code System.Logger.class.isAssignableFrom(frame.getDeclaringClass())}, so the frame's
 * declaring class must be the interface itself and must be the same mirror as the class literal. Also the class
 * literal against Class.forName of the same nested interface, every listed interface against itself, and
 * interface-to-interface and interface-array pairs. Prints values only; the host diff is the gate.
 */
public class DefaultFrameProbe
{
    interface Greeter
    {
        default List<StackWalker.StackFrame> frames()
        {
            StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            return walker.walk(s -> s.limit(2).collect(Collectors.toList()));
        }
    }

    static final class Impl implements Greeter
    {
    }

    public static void main(String[] args) throws Exception
    {
        Class<?> byName = Class.forName("java.lang.System$Logger");
        System.out.println("forName==literal " + (byName == System.Logger.class));
        System.out.println("literal.isAssignableFrom(literal) "
                + System.Logger.class.isAssignableFrom(System.Logger.class));
        System.out.println("literal.isAssignableFrom(byName) " + System.Logger.class.isAssignableFrom(byName));
        System.out.println("byName interface=" + byName.isInterface() + " name=" + byName.getName());
        // Self-assignability across many baked interfaces, so both odd and even interface IDs are covered, then
        // interface-extends-interface both ways and interface-element arrays.
        Class<?>[] ifaces = {
            Runnable.class, Comparable.class, CharSequence.class, Iterable.class, java.util.Collection.class,
            List.class, java.util.Map.class, java.util.Map.Entry.class, java.util.Set.class, java.util.Deque.class,
            java.util.Queue.class, java.util.RandomAccess.class, java.io.Serializable.class, Cloneable.class,
            AutoCloseable.class, java.io.Closeable.class, Appendable.class, java.util.function.Function.class,
            java.util.function.Supplier.class, java.util.Iterator.class, System.Logger.class };
        int selfFalse = 0;
        for (Class<?> c : ifaces)
        {
            if (!c.isAssignableFrom(c))
            {
                selfFalse += 1;
                System.out.println("  NOT self-assignable: " + c.getName());
            }
        }
        System.out.println("interfaces=" + ifaces.length + " not self-assignable=" + selfFalse);
        System.out.println("Collection<-List " + java.util.Collection.class.isAssignableFrom(List.class)
                + " List<-Collection " + List.class.isAssignableFrom(java.util.Collection.class)
                + " Iterable<-Deque " + Iterable.class.isAssignableFrom(java.util.Deque.class)
                + " Closeable<-AutoCloseable " + java.io.Closeable.class.isAssignableFrom(AutoCloseable.class));
        System.out.println("Runnable[]<-Runnable[] " + Runnable[].class.isAssignableFrom(Runnable[].class)
                + " Object[]<-Runnable[] " + Object[].class.isAssignableFrom(Runnable[].class)
                + " Runnable[]<-Object[] " + Runnable[].class.isAssignableFrom(Object[].class));
        List<StackWalker.StackFrame> fs = new Impl().frames();
        for (StackWalker.StackFrame f : fs)
        {
            Class<?> d = f.getDeclaringClass();
            System.out.println("frame " + f.getClassName() + "." + f.getMethodName() + " decl=" + d.getName()
                    + " ==Greeter " + (d == Greeter.class) + " Greeter.isAssignableFrom "
                    + Greeter.class.isAssignableFrom(d));
        }
    }
}
