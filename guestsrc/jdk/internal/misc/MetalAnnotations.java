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
package jdk.internal.misc;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * The {@link Annotation} contract -- {@code equals}, {@code hashCode}, {@code toString} -- for the annotation
 * instances this VM builds. Stock implements it in {@code AnnotationInvocationHandler} over a {@code Proxy}; an
 * instance here is a synthesised object whose element methods are itable thunks, so the VM points that object's
 * {@code equals}/{@code hashCode}/{@code toString} vtable slots (and its {@code Annotation} itable entry) at the
 * three methods below ({@code Loader.annoTibFor}). Each is STATIC with the receiver as its first parameter, which
 * is the same register layout as the instance method it stands in for.
 *
 * <p>The semantics are stock's: equal when of the same annotation type with equal elements (arrays by content,
 * float and double by bits), the hash the sum of {@code (127 * name.hashCode()) ^ valueHash} over the elements,
 * and the source-like string with stock's value formatting. One stated difference: with more than one element,
 * {@code toString} lists them in DECLARATION order; stock's order comes from its parser's member map.
 *
 * <p>Element values are read through the element methods themselves, on a fresh accessible {@link Method}: the
 * annotation type may be non-public, and access is checked against this class.
 */
public final class MetalAnnotations
{
    private MetalAnnotations()
    {
    }

    /** The element methods of {@code type}, in declaration order: public, abstract, no parameters, not synthetic. */
    private static Method[] members(Class<? extends Annotation> type)
    {
        Method[] all = type.getDeclaredMethods();
        Method[] out = new Method[all.length];
        int n = 0;
        for (Method m : all)
        {
            int mod = m.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isAbstract(mod) && !m.isSynthetic() && m.getParameterCount() == 0)
            {
                m.setAccessible(true);
                out[n] = m;
                n += 1;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static Object value(Method m, Object anno)
    {
        try
        {
            return m.invoke(anno);
        }
        catch (IllegalAccessException | InvocationTargetException e)
        {
            throw new IllegalStateException("annotation element " + m.getName(), e);
        }
    }

    /** {@code Annotation.equals}: same annotation type, every element equal. */
    public static boolean annoEquals(Object self, Object o)
    {
        if (o == self)
        {
            return true;
        }
        Class<? extends Annotation> type = ((Annotation) self).annotationType();
        if (!type.isInstance(o))
        {
            return false;
        }
        for (Method m : members(type))
        {
            if (!memberValueEquals(value(m, self), value(m, o)))
            {
                return false;
            }
        }
        return true;
    }

    /** {@code Annotation.hashCode}: the sum over the elements of {@code (127 * name.hashCode()) ^ valueHash}. */
    public static int annoHashCode(Object self)
    {
        int result = 0;
        for (Method m : members(((Annotation) self).annotationType()))
        {
            result += (127 * m.getName().hashCode()) ^ memberValueHashCode(value(m, self));
        }
        return result;
    }

    /** {@code Annotation.toString}: {@code @pkg.Type(name=value, ...)}, a lone {@code value} element unnamed. */
    public static String annoToString(Object self)
    {
        Class<? extends Annotation> type = ((Annotation) self).annotationType();
        StringBuilder result = new StringBuilder(128);
        result.append('@');
        String cn = type.getCanonicalName();
        result.append(cn == null ? "<no canonical name>" : cn);
        result.append('(');
        Method[] ms = members(type);
        boolean loneValue = ms.length == 1;
        for (int i = 0; i < ms.length; i++)
        {
            if (i > 0)
            {
                result.append(", ");
            }
            String key = ms[i].getName();
            if (!loneValue || !"value".equals(key))
            {
                result.append(key);
                result.append('=');
            }
            result.append(memberValueToString(value(ms[i], self)));
        }
        result.append(')');
        return result.toString();
    }

    private static boolean memberValueEquals(Object v1, Object v2)
    {
        Class<?> type = v1.getClass();
        if (!type.isArray())
        {
            return v1.equals(v2);
        }
        if (v1 instanceof Object[] a1 && v2 instanceof Object[] a2)
        {
            return Arrays.equals(a1, a2);
        }
        if (v2.getClass() != type)
        {
            return false;
        }
        if (type == byte[].class)
        {
            return Arrays.equals((byte[]) v1, (byte[]) v2);
        }
        if (type == char[].class)
        {
            return Arrays.equals((char[]) v1, (char[]) v2);
        }
        if (type == double[].class)
        {
            return Arrays.equals((double[]) v1, (double[]) v2);
        }
        if (type == float[].class)
        {
            return Arrays.equals((float[]) v1, (float[]) v2);
        }
        if (type == int[].class)
        {
            return Arrays.equals((int[]) v1, (int[]) v2);
        }
        if (type == long[].class)
        {
            return Arrays.equals((long[]) v1, (long[]) v2);
        }
        if (type == short[].class)
        {
            return Arrays.equals((short[]) v1, (short[]) v2);
        }
        return Arrays.equals((boolean[]) v1, (boolean[]) v2);
    }

    private static int memberValueHashCode(Object value)
    {
        Class<?> type = value.getClass();
        if (!type.isArray())
        {
            return value.hashCode();
        }
        if (type == byte[].class)
        {
            return Arrays.hashCode((byte[]) value);
        }
        if (type == char[].class)
        {
            return Arrays.hashCode((char[]) value);
        }
        if (type == double[].class)
        {
            return Arrays.hashCode((double[]) value);
        }
        if (type == float[].class)
        {
            return Arrays.hashCode((float[]) value);
        }
        if (type == int[].class)
        {
            return Arrays.hashCode((int[]) value);
        }
        if (type == long[].class)
        {
            return Arrays.hashCode((long[]) value);
        }
        if (type == short[].class)
        {
            return Arrays.hashCode((short[]) value);
        }
        if (type == boolean[].class)
        {
            return Arrays.hashCode((boolean[]) value);
        }
        return Arrays.hashCode((Object[]) value);
    }

    private static String memberValueToString(Object value)
    {
        Class<?> type = value.getClass();
        if (!type.isArray())
        {
            if (type == Class.class)
            {
                return toSourceString((Class<?>) value);
            }
            if (type == String.class)
            {
                return toSourceString((String) value);
            }
            if (type == Character.class)
            {
                return toSourceString((char) (Character) value);
            }
            if (type == Double.class)
            {
                return toSourceString((double) (Double) value);
            }
            if (type == Float.class)
            {
                return toSourceString((float) (Float) value);
            }
            if (type == Long.class)
            {
                return toSourceString((long) (Long) value);
            }
            if (type == Byte.class)
            {
                return toSourceString((byte) (Byte) value);
            }
            if (value instanceof Enum<?> v)
            {
                return v.name();
            }
            return value.toString();
        }
        StringBuilder sb = new StringBuilder("{");
        int n = java.lang.reflect.Array.getLength(value);
        for (int i = 0; i < n; i++)
        {
            if (i > 0)
            {
                sb.append(", ");
            }
            Object e = java.lang.reflect.Array.get(value, i);
            if (type == int[].class || type == short[].class || type == boolean[].class)
            {
                sb.append(e);
            }
            else if (type == Object[].class || (!type.getComponentType().isPrimitive()
                    && type.getComponentType() != Class.class && type.getComponentType() != String.class
                    && !type.getComponentType().isEnum()))
            {
                sb.append(String.valueOf(e));
            }
            else
            {
                sb.append(memberValueToString(e));
            }
        }
        return sb.append('}').toString();
    }

    private static String toSourceString(Class<?> clazz)
    {
        String cn = clazz.getCanonicalName();
        return (cn == null ? "<no canonical name>" : cn) + ".class";
    }

    private static String toSourceString(float f)
    {
        if (Float.isFinite(f))
        {
            return Float.toString(f) + "f";
        }
        if (Float.isInfinite(f))
        {
            return (f < 0.0f) ? "-1.0f/0.0f" : "1.0f/0.0f";
        }
        return "0.0f/0.0f";
    }

    private static String toSourceString(double d)
    {
        if (Double.isFinite(d))
        {
            return Double.toString(d);
        }
        if (Double.isInfinite(d))
        {
            return (d < 0.0f) ? "-1.0/0.0" : "1.0/0.0";
        }
        return "0.0/0.0";
    }

    private static String toSourceString(char c)
    {
        return "'" + quote(c, true) + "'";
    }

    private static String quote(char ch, boolean charContext)
    {
        switch (ch)
        {
            case '\b':
                return "\\b";
            case '\f':
                return "\\f";
            case '\n':
                return "\\n";
            case '\r':
                return "\\r";
            case '\t':
                return "\\t";
            case '\'':
                return charContext ? "\\'" : "'";
            case '\"':
                return charContext ? "\"" : "\\\"";
            case '\\':
                return "\\\\";
            default:
                return (ch >= ' ' && ch <= '~') ? String.valueOf(ch) : String.format("\\u%04x", (int) ch);
        }
    }

    private static String toSourceString(byte b)
    {
        return String.format("(byte)0x%02x", b);
    }

    private static String toSourceString(long ell)
    {
        return String.valueOf(ell) + "L";
    }

    private static String toSourceString(String s)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            sb.append(quote(s.charAt(i), false));
        }
        sb.append('"');
        return sb.toString();
    }
}
