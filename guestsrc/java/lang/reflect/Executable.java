/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-14
 */
package java.lang.reflect;

import java.lang.annotation.Annotation;
import magic.Magic;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * {@code java.lang.reflect.Executable} for joe-ng: the shared supertype of {@link Method} and
 * {@link Constructor}, which is what stock declares and what this overlay had been missing.
 *
 * <p>WHY IT EXISTS AT ALL, since both subclasses already carried every member it declares: a caller with an
 * {@code Executable}-typed reference emits {@code invokevirtual java/lang/reflect/Executable.<m>}, and with no
 * such class the receiver's chain (Constructor -> AccessibleObject) does not contain it. JUnit's
 * {@code ExtensionUtils.registerExtensionsFromExecutableParameters} is exactly that shape and reported
 * {@code VIRTUALRESOLVE FAILED java/lang/reflect/Constructor.getParameters()} -- the late-virtual tier naming
 * the RECEIVER's class, not the one in the descriptor.
 *
 * <p>NOT TAKEN FROM THE JDK 26 SOURCE, and that is a stated exception to the standing overlay rule rather than
 * a shortcut. Stock {@code Executable} is 833 lines built on {@code sun.reflect.generics.*} (a generic
 * signature parser and type-factory) and {@code sun.reflect.annotation.*} (the annotation parser and proxy
 * runtime) -- both denied here, and both far larger than what this VM can execute. The same reasoning already
 * recorded for the {@code ServiceLoader} overlay applies: the stock implementation routes through subsystems
 * this VM deliberately does not carry, so no faithful copy could work whatever its shape.
 *
 * <p>The four members below are ABSTRACT rather than reimplemented because {@link Method} and
 * {@link Constructor} already answer them from their own registry state; this class only gives callers a type
 * to dispatch through.
 */
public abstract class Executable extends AccessibleObject implements Member, GenericDeclaration
{
    Executable()
    {
    }

    public abstract Class<?> getDeclaringClass();

    public abstract String getName();

    public abstract int getModifiers();

    public abstract int getParameterCount();

    /**
     * This executable's slot in the VM's METHOD REGISTRY, which is the key every descriptor- and
     * annotation-reading native takes. Package-private and abstract because the two subclasses each already
     * hold it; hoisting the ACCESSOR rather than the field keeps their constructors untouched.
     */
    abstract int registryIndex();

    /**
     * The declared parameter types, from the registry's copy of the DESCRIPTOR.
     *
     * <p>Not answerable from the {@code paramChars} both subclasses cache -- those keep only the first
     * character of each parameter, so every reference type looks alike. {@link Method} overrides this with the
     * identical body against its own native; the copy here is what makes a {@link Constructor} answer, which
     * it never could before. An ARRAY parameter resolves to its array class ({@code String[].class}).
     */
    public Class<?>[] getParameterTypes()
    {
        Object o = paramTypes0(registryIndex());
        if (o == null)
        {
            return new Class<?>[0];
        }
        return (Class<?>[]) o;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.methodParamTypes}): registry index -> Class[]. */
    private static native Object paramTypes0(int rgIndex);

    /** Stock's: the type variables this method or constructor declares; empty when it declares none. */
    public abstract TypeVariable<?>[] getTypeParameters();

    /**
     * Stock's: the parameter types as this member's {@code Signature} attribute declares them --
     * {@code List<String>} rather than {@code List} -- or the erased {@link #getParameterTypes} when it has no
     * signature, which is stock's own answer for a non-generic member.
     */
    public Type[] getGenericParameterTypes()
    {
        String sig = genericSignature();
        if (sig == null)
        {
            Class<?>[] p = getParameterTypes();
            Type[] t = new Type[p.length];
            for (int i = 0; i < p.length; i++)
            {
                t[i] = p[i];
            }
            return t;
        }
        return genericInfo(sig).getParameterTypes();
    }

    /**
     * This member's {@code Signature} attribute, or null. Read afresh on each call: a stock {@code Executable}
     * caches its repository in a field, and this one keeps no state beyond what its subclasses already hold.
     */
    String genericSignature()
    {
        return signature0(registryIndex());
    }

    /**
     * The generic repository over {@code signature}, built in this member's own SCOPE so that a type variable
     * resolves to the method's or constructor's own before its class's -- stock's split: {@link Method} answers
     * a {@code MethodRepository}, {@link Constructor} a {@code ConstructorRepository}.
     */
    abstract sun.reflect.generics.repository.ConstructorRepository genericInfo(String signature);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodSignature}): registry index -> Signature. */
    private static native String signature0(int rgIndex);

    /**
     * A {@link magic.Magic#callN} argument buffer for {@code total} arguments (the receiver counts), laid out as
     * the JIT passes them: arguments 0..15 in x0..x15, or -- past 16 -- 0..14 in x0..x14 and x15 POINTING at the
     * rest, which follow in the same array. {@link #putArg} places each one. The buffer was a fixed
     * {@code long[8]}, so a ninth argument was written past its end.
     */
    static long[] argBuffer(int total)
    {
        if (total <= 16)
        {
            return new long[16];
        }
        long[] slots = new long[total + 1];
        slots[15] = Magic.addrOf(slots) + 24L + 16L * 8L;   // &slots[16]: the collector does not move objects
        return slots;
    }

    /** Store argument {@code k} of {@code total} into an {@link #argBuffer}. */
    static void putArg(long[] slots, int total, int k, long value)
    {
        slots[(total > 16 && k >= 15) ? k + 1 : k] = value;
    }

    /**
     * Argument {@code a} for a parameter of type {@code t}, as the raw 64-bit register value the JIT expects,
     * converted as stock converts it: a reference must be an instance of {@code t} (or null); a primitive is
     * UNBOXED and then WIDENED (JLS 5.1.2) -- {@code Integer} to {@code long}, {@code Character} to {@code int},
     * {@code Float} to {@code double} -- and anything else, a narrowing or a null, is
     * {@code IllegalArgumentException}. This used to CAST to the exact wrapper, so a widening threw
     * {@code ClassCastException} and a wrong reference type passed through unchecked. FP values travel as their
     * raw bits, which is how the JIT holds them.
     */
    static long convertArg(Class<?> t, Object a)
    {
        if (!t.isPrimitive())
        {
            if (a != null && !t.isInstance(a))
            {
                throw new IllegalArgumentException("argument type mismatch");
            }
            return a == null ? 0L : Magic.addrOf(a);
        }
        if (t == boolean.class)
        {
            if (a instanceof Boolean b)
            {
                return b ? 1L : 0L;
            }
            throw new IllegalArgumentException("argument type mismatch");
        }
        int tr = primRank(t);
        int sr;
        if (a instanceof Byte)
        {
            sr = 1;
        }
        else if (a instanceof Short)
        {
            sr = 2;
        }
        else if (a instanceof Character)
        {
            sr = 3;                                     // char widens only to int and up
        }
        else if (a instanceof Integer)
        {
            sr = 3;
        }
        else if (a instanceof Long)
        {
            sr = 4;
        }
        else if (a instanceof Float)
        {
            sr = 5;
        }
        else if (a instanceof Double)
        {
            sr = 6;
        }
        else
        {
            throw new IllegalArgumentException("argument type mismatch");
        }
        boolean ok = t == char.class ? a instanceof Character : sr <= tr;
        if (!ok)
        {
            throw new IllegalArgumentException("argument type mismatch");
        }
        if (t == float.class)
        {
            float f = sr >= 5 ? ((Number) a).floatValue() : (float) integralValue(a);
            return Float.floatToRawIntBits(f);
        }
        if (t == double.class)
        {
            double d = sr >= 5 ? ((Number) a).doubleValue() : (double) integralValue(a);
            return Double.doubleToRawLongBits(d);
        }
        return integralValue(a);
    }

    /** byte 1, short 2, char/int 3, long 4, float 5, double 6 -- the widening order of JLS 5.1.2. */
    private static int primRank(Class<?> t)
    {
        if (t == byte.class)
        {
            return 1;
        }
        if (t == short.class)
        {
            return 2;
        }
        if (t == char.class || t == int.class)
        {
            return 3;
        }
        if (t == long.class)
        {
            return 4;
        }
        if (t == float.class)
        {
            return 5;
        }
        return 6;
    }

    /** The value of an integral box (Character included), sign-extended. */
    private static long integralValue(Object a)
    {
        if (a instanceof Character ch)
        {
            return ch.charValue();
        }
        return ((Number) a).longValue();
    }

    /** Stock's argument-count check for {@code invoke}/{@code newInstance}. */
    static void checkArgCount(Object[] args, int expected)
    {
        int n = args == null ? 0 : args.length;
        if (n != expected)
        {
            throw new IllegalArgumentException("wrong number of arguments: " + n + " expected: " + expected);
        }
    }

    /**
     * A scratch array for {@code methodInfo0}, which writes one type character per parameter with NO bound.
     * Every caller passed a {@code byte[8]}, so a ninth parameter wrote past the array into the heap. JVMS 4.3.3
     * caps a descriptor at 255 parameter slots; trim the result with {@link #paramChars}.
     */
    static byte[] paramCharScratch()
    {
        return new byte[255];
    }

    /** The first {@code n} characters of a {@link #paramCharScratch}, kept per member. */
    static byte[] paramChars(byte[] scratch, int n)
    {
        return Arrays.copyOf(scratch, n);
    }

    /** Stock's. */
    void printModifiersIfNonzero(StringBuilder sb, int mask, boolean isDefault)
    {
        int mod = getModifiers() & mask;

        if (mod != 0 && !isDefault)
        {
            sb.append(Modifier.toString(mod)).append(' ');
        }
        else
        {
            int access_mod = mod & Modifier.ACCESS_MODIFIERS;
            if (access_mod != 0)
            {
                sb.append(Modifier.toString(access_mod)).append(' ');
            }
            if (isDefault)
            {
                sb.append("default ");
            }
            mod = (mod & ~Modifier.ACCESS_MODIFIERS);
            if (mod != 0)
            {
                sb.append(Modifier.toString(mod)).append(' ');
            }
        }
    }

    /** Stock's: modifiers, the subclass's header, erased parameter types, then any {@code throws} clause. */
    String sharedToString(int modifierMask, boolean isDefault, Class<?>[] parameterTypes, Class<?>[] exceptionTypes)
    {
        try
        {
            StringBuilder sb = new StringBuilder();

            printModifiersIfNonzero(sb, modifierMask, isDefault);
            specificToStringHeader(sb);
            sb.append(Arrays.stream(parameterTypes)
                      .map(Type::getTypeName)
                      .collect(Collectors.joining(",", "(", ")")));
            if (exceptionTypes.length > 0)
            {
                sb.append(Arrays.stream(exceptionTypes)
                          .map(Type::getTypeName)
                          .collect(Collectors.joining(",", " throws ", "")));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return "<" + e + ">";
        }
    }

    abstract void specificToStringHeader(StringBuilder sb);

    /** Stock's. */
    static String typeVarBounds(TypeVariable<?> typeVar)
    {
        Type[] bounds = typeVar.getBounds();
        if (bounds.length == 1 && bounds[0].equals(Object.class))
        {
            return typeVar.getName();
        }
        else
        {
            return typeVar.getName() + " extends "
                    + Arrays.stream(bounds)
                    .map(Type::getTypeName)
                    .collect(Collectors.joining(" & "));
        }
    }

    /** Stock's: as {@link #sharedToString}, over the GENERIC types, with type parameters and varargs. */
    String sharedToGenericString(int modifierMask, boolean isDefault)
    {
        try
        {
            StringBuilder sb = new StringBuilder();

            printModifiersIfNonzero(sb, modifierMask, isDefault);

            TypeVariable<?>[] typeparms = getTypeParameters();
            if (typeparms.length > 0)
            {
                sb.append(Arrays.stream(typeparms)
                          .map(Executable::typeVarBounds)
                          .collect(Collectors.joining(",", "<", "> ")));
            }

            specificToGenericStringHeader(sb);

            sb.append('(');
            StringJoiner sj = new StringJoiner(",");
            Type[] params = getGenericParameterTypes();
            for (int j = 0; j < params.length; j++)
            {
                String param = params[j].getTypeName();
                if (isVarArgs() && (j == params.length - 1))  // replace T[] with T...
                {
                    param = param.replaceFirst("\\[\\]$", "...");
                }
                sj.add(param);
            }
            sb.append(sj.toString());
            sb.append(')');

            Type[] exceptionTypes = getGenericExceptionTypes();
            if (exceptionTypes.length > 0)
            {
                sb.append(Arrays.stream(exceptionTypes)
                          .map(Type::getTypeName)
                          .collect(Collectors.joining(",", " throws ", "")));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return "<" + e + ">";
        }
    }

    abstract void specificToGenericStringHeader(StringBuilder sb);

    public abstract String toGenericString();

    /** Stock's. */
    public boolean isVarArgs()
    {
        return (getModifiers() & Modifier.VARARGS) != 0;
    }

    /**
     * The classes this member declares it {@code throws}, from its {@code Exceptions} attribute, in declared
     * order; empty when it declares none. A FRESH ARRAY EACH CALL, as stock's copy is. Each name resolves
     * through {@link Class#forName}, which on this VM also initializes the class.
     */
    public Class<?>[] getExceptionTypes()
    {
        int rg = registryIndex();
        int n = 0;
        while (exceptionName0(rg, n) != null)
        {
            n += 1;
        }
        Class<?>[] out = new Class<?>[n];
        for (int i = 0; i < n; i++)
        {
            String name = exceptionName0(rg, i).replace('/', '.');
            try
            {
                out[i] = Class.forName(name, false, null);
            }
            catch (ClassNotFoundException e)
            {
                NoClassDefFoundError err = new NoClassDefFoundError(name);
                err.initCause(e);
                throw err;
            }
        }
        return out;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodExceptionName}): the i-th throws name, or null. */
    private static native String exceptionName0(int rgIndex, long i);

    /**
     * Stock's: the {@code throws} clause as the {@code Signature} attribute declares it -- a thrown type
     * variable stays {@code X} -- or the erased {@link #getExceptionTypes} when the signature names none.
     */
    public Type[] getGenericExceptionTypes()
    {
        Type[] result;
        String sig = genericSignature();
        if (sig != null && ((result = genericInfo(sig).getExceptionTypes()).length > 0))
        {
            return result;
        }
        return getExceptionTypes();
    }

    /**
     * Stock's: the parameters, from the {@code MethodParameters} attribute when the class has one -- javac writes
     * it for every class compiled with {@code -parameters}, and for an inner class's or enum's constructor even
     * without, flagging the implicit outer instance {@code MANDATED} and the enum's name and ordinal
     * {@code SYNTHETIC} -- and synthesized as {@code argN} with no modifiers when it has none. A fresh array each
     * call; the parameters themselves are cached, as stock caches them.
     */
    public Parameter[] getParameters()
    {
        return parameterData().parameters.clone();
    }

    private Parameter[] synthesizeAllParams()
    {
        final int realparams = getParameterCount();
        final Parameter[] out = new Parameter[realparams];
        for (int i = 0; i < realparams; i++)
        {
            out[i] = new Parameter(null, 0, this, i);
        }
        return out;
    }

    private void verifyParameters(final Parameter[] parameters)
    {
        final int mask = Modifier.FINAL | Modifier.SYNTHETIC | Modifier.MANDATED;

        if (getParameterCount() != parameters.length)
        {
            throw new MalformedParametersException("Wrong number of parameters in MethodParameters attribute");
        }

        for (Parameter parameter : parameters)
        {
            final String name = parameter.getRealName();
            final int mods = parameter.getModifiers();

            if (name != null)
            {
                if (name.isEmpty() || name.indexOf('.') != -1 || name.indexOf(';') != -1 || name.indexOf('[') != -1
                        || name.indexOf('/') != -1)
                {
                    throw new MalformedParametersException("Invalid parameter name \"" + name + "\"");
                }
            }

            if (mods != (mods & mask))
            {
                throw new MalformedParametersException("Invalid parameter modifiers");
            }
        }
    }

    boolean hasRealParameterData()
    {
        return parameterData().isReal;
    }

    private ParameterData parameterData()
    {
        ParameterData parameterData = this.parameterData;
        if (parameterData != null)
        {
            return parameterData;
        }
        Parameter[] tmp = getParameters0();
        if (tmp == null)
        {
            tmp = synthesizeAllParams();
            parameterData = new ParameterData(tmp, false);
        }
        else
        {
            verifyParameters(tmp);
            parameterData = new ParameterData(tmp, true);
        }
        return this.parameterData = parameterData;
    }

    private transient ParameterData parameterData;

    record ParameterData(Parameter[] parameters, boolean isReal)
    {
    }

    /**
     * Stock's native, in Java over the VM's attribute readers: one {@link Parameter} per {@code MethodParameters}
     * entry, with its name (null where the entry has none) and flags, or null when there is no such attribute.
     */
    private Parameter[] getParameters0()
    {
        int rg = registryIndex();
        int n = (int) paramMeta0(rg, -1L);
        if (n < 0)
        {
            return null;
        }
        Parameter[] out = new Parameter[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = new Parameter(paramName0(rg, i), (int) paramMeta0(rg, i), this, i);
        }
        return out;
    }

    /** Stock's: the parameter types, shared rather than copied -- here {@link #getParameterTypes}, fresh. */
    Class<?>[] getSharedParameterTypes()
    {
        return getParameterTypes();
    }

    /** Stock's: whether this member has a {@code Signature} attribute. */
    boolean hasGenericInformation()
    {
        return genericSignature() != null;
    }

    /**
     * Stock's: one generic type per parameter INCLUDING implicit and synthetic ones, which a {@code Signature}
     * leaves out -- so it lines the two up by the {@code MethodParameters} flags when it has them, and falls
     * back to the erased types when the counts disagree and it cannot tell which parameters are missing.
     */
    Type[] getAllGenericParameterTypes()
    {
        final boolean genericInfo = hasGenericInformation();
        if (!genericInfo)
        {
            return getParameterTypes();
        }
        final boolean realParamData = hasRealParameterData();
        final Type[] genericParamTypes = getGenericParameterTypes();
        final Type[] nonGenericParamTypes = getSharedParameterTypes();
        if (realParamData)
        {
            if (getDeclaringClass().isRecord() && this instanceof Constructor)
            {
                if (genericParamTypes.length == nonGenericParamTypes.length)
                {
                    return genericParamTypes;
                }
                return nonGenericParamTypes.clone();
            }
            final Type[] out = new Type[nonGenericParamTypes.length];
            final Parameter[] params = getParameters();
            int fromidx = 0;
            for (int i = 0; i < out.length; i++)
            {
                final Parameter param = params[i];
                if (param.isSynthetic() || param.isImplicit())
                {
                    out[i] = nonGenericParamTypes[i];
                }
                else
                {
                    out[i] = genericParamTypes[fromidx];
                    fromidx++;
                }
            }
            return out;
        }
        return genericParamTypes.length == nonGenericParamTypes.length ? genericParamTypes : getParameterTypes();
    }

    /**
     * Stock's {@code sharedGetParameterAnnotations}: one row per parameter from the
     * {@code RuntimeVisibleParameterAnnotations} attribute, all rows empty when there is none. The attribute
     * can have FEWER rows than the descriptor has parameters -- javac writes none for an inner class
     * constructor's outer instance or an enum constructor's name and ordinal -- and
     * {@link #handleParameterNumberMismatch} decides, per subclass as stock does, whether to pad the leading
     * rows or refuse.
     */
    public Annotation[][] getParameterAnnotations()
    {
        Class<?>[] parameterTypes = getParameterTypes();
        int numParameters = parameterTypes.length;
        int rg = registryIndex();
        int rows = (int) paramMeta0(rg, -2L);
        if (rows < 0)
        {
            return new Annotation[numParameters][0];
        }
        Annotation[][] result = new Annotation[rows][];
        for (int i = 0; i < rows; i++)
        {
            Annotation[] row = paramAnnoRow0(rg, i);
            result[i] = row == null ? new Annotation[0] : row;
        }
        if (result.length != numParameters && handleParameterNumberMismatch(result.length, parameterTypes))
        {
            Annotation[][] tmp = new Annotation[numParameters][];
            System.arraycopy(result, 0, tmp, numParameters - result.length, result.length);
            for (int i = 0; i < numParameters - result.length; i++)
            {
                tmp[i] = new Annotation[0];
            }
            result = tmp;
        }
        return result;
    }

    abstract boolean handleParameterNumberMismatch(int resultLength, Class<?>[] parameterTypes);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodParamMeta}): see {@code Loader.methodParamMeta}. */
    private static native long paramMeta0(int rgIndex, long k);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodParamName}): an entry's name, or null. */
    private static native String paramName0(int rgIndex, long k);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodParamAnnoRow}): one attribute row, or null. */
    private static native Annotation[] paramAnnoRow0(int rgIndex, long k);

    /**
     * True if this method or constructor carries the given annotation. Marker level: presence only, no element
     * values.
     *
     * <p>This and the four annotation readers below live HERE, not on {@link Method}, because the registry
     * holds a constructor's {@code <init>} like any other method: keyed on {@link #registryIndex}, they answer
     * for a {@link Constructor} too, which stock does and which this VM never did -- a constructor's
     * annotations fell through to the base class.
     *
     * <p>Only annotations declared {@code @Retention(RUNTIME)} are visible -- javac writes anything else into
     * RuntimeINVISIBLEAnnotations, i.e. an annotation without RUNTIME retention is not merely unreadable here,
     * it is absent from the classfile.
     */
    public boolean isAnnotationPresent(Class<? extends java.lang.annotation.Annotation> anno)
    {
        if (anno == null)
        {
            return false;
        }
        return annoPresent0(registryIndex(), descriptorOf(anno)) != 0;
    }

    /** "org.junit.jupiter.api.Test" -> the field descriptor "Lorg/junit/jupiter/api/Test;" the classfile holds. */
    private static byte[] descriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        int i = 0;
        while (i < n.length())
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
            i += 1;
        }
        out[n.length() + 1] = (byte) ';';
        return out;
    }

    /**
     * The annotation INSTANCE, or null when absent. The object the VM returns implements the annotation
     * interface: its Type carries an itable for that interface whose slots are two-instruction thunks reading
     * the element values out of the instance (see {@code Loader.annoTibFor}). So {@code getAnnotation(X.class)
     * .value()} is an ordinary interface dispatch, not a special case.
     *
     * <p>Element values that need a class RESOLVED -- a {@code Class} element, an enum constant, a nested
     * annotation -- read null in this increment, because resolving one mid-walk would re-parse a blob and
     * clobber the classfile cursor the walk is using.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(registryIndex(), descriptorOf(anno));
    }

    /**
     * The annotation DECLARED DIRECTLY on this method or constructor, or null.
     *
     * <p>IDENTICAL to {@link #getAnnotation}, and for a method that is EXACT rather than an approximation --
     * unlike the {@code Class} pair, where the two differ by {@code @Inherited}. JLS 9.6.4.3 gives
     * {@code @Inherited} effect on class declarations ALONE, and an overriding method does not inherit the
     * annotations of the method it overrides, so for a method "declared" and "present" are the same set.
     * Stock agrees by construction: {@code Executable.getAnnotation} reads {@code declaredAnnotations()}.
     *
     * <p>THE BOUND IS LOAD-BEARING, for the third time in this family. {@code <T extends Annotation>} erases
     * the return to {@code Ljava/lang/annotation/Annotation;} -- the descriptor JUnit's
     * {@code AnnotationUtils.findAnnotation} references. Declared as a plain {@code <T>} it would erase to
     * {@code Ljava/lang/Object;}: a DIFFERENT METHOD that resolves nowhere and traps.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getDeclaredAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(registryIndex(), descriptorOf(anno));
    }

    /**
     * Every annotation DECLARED on this method or constructor. Never null; empty when there are none.
     *
     * <p>{@code findAnnotation} walks this to find META-ANNOTATIONS -- an annotation carried by another
     * annotation -- so an empty answer would not fail, it would silently report "not annotated". That is the
     * shape this VM keeps getting bitten by, which is why it enumerates rather than answering empty.
     *
     * <p>A fresh array each call, because stock specifies the caller may modify the returned one. An
     * annotation whose interface is not loaded is omitted and named on the console: there is no itable to
     * give such an instance, and a null element reads as "present but broken".
     */
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        java.lang.annotation.Annotation[] a = annoAll0(registryIndex());
        return a == null ? new java.lang.annotation.Annotation[0] : a;
    }

    /**
     * As {@link #getDeclaredAnnotations}. For a method or constructor the two coincide EXACTLY, for the reason given on
     * {@link #getDeclaredAnnotation}: {@code @Inherited} does not reach method declarations, so there is no
     * indirectly-present annotation for this to add. No divergence from stock here.
     */
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodAnnoAll}): registry index -> Annotation[]. */
    private static native java.lang.annotation.Annotation[] annoAll0(int rgIndex);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.annoGet}): registry index + descriptor -> instance. */
    private static native Object annoGet0(int rgIndex, byte[] descriptor);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.annoPresent}): registry index + descriptor -> 0/1. */
    private static native int annoPresent0(int rgIndex, byte[] descriptor);
}
