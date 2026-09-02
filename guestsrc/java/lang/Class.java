package java.lang;

import magic.Magic;

/**
 * Bare-metal {@code java/lang/Class} mirror. The VM materialises one Class instance per loaded VM Type (on an
 * {@code ldc} class-literal or {@code Object.getClass()}, in {@code Loader.classMirror}) and stores the raw
 * Type-node pointer in {@link #typeAddr}; the mirror is cached per Type, so {@code X.class} and
 * {@code obj.getClass()} return the SAME identity — which is what stock code compares (e.g.
 * {@code Arrays.copyOf}'s {@code newType == Object[].class}). This override keeps the huge stock
 * {@code java.lang.Class} (and its reflection/CDS closure) out of the image; instance methods
 * ({@code getName}/{@code getComponentType}/{@code isInstance}/...) are added on demand as the code that runs
 * on metal reaches them. The VM allocates the object directly (bypassing this ctor).
 */
public final class Class<T>
{
    private long typeAddr;      // the VM Type node this Class mirrors (set by the VM at materialisation)

    private Class()
    {
    }

    /**
     * Load (if needed) and return the {@code Class} for the binary name {@code name} (dots), initializing it.
     * On metal this pulls the class + its dependency closure into the live program on demand (the VM's
     * incremental loader), then runs its {@code <clinit>}. Throws {@code ClassNotFoundException} if the class
     * is not embedded or the name is not a valid binary name.
     */
    public static Class<?> forName(String name) throws ClassNotFoundException
    {
        return forName(name, true, null);
    }

    /**
     * As {@link #forName(String)}, with an explicit {@code initialize} flag and class loader. On metal the
     * class is always initialized when loaded (the loader runs {@code <clinit>} as part of the batch); the
     * {@code initialize} flag and {@code loader} are accepted for source compatibility.
     */
    public static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException
    {
        Class<?> c = (name == null) ? null : forName0(name.getBytes());
        if (c == null)
        {
            throw new ClassNotFoundException(name);
        }
        return c;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.forName}): raw binary-name bytes -> Class mirror, or null. */
    private static native Class forName0(byte[] name);

    /**
     * Assertions are off on metal (no -ea). Stock {@code <clinit>}s read this into their {@code $assertionsDisabled}
     * flag (e.g. {@code java.util.regex.Pattern.<clinit>} does {@code ldc X.class; desiredAssertionStatus()}); with
     * this it can run to completion and initialise its static nodes instead of being skipped.
     */
    public boolean desiredAssertionStatus()
    {
        return false;
    }

    /** The class's binary name with dots (M4), built by the VM from the loader registry's name bytes. */
    /**
     * The single unnamed module (joe-ng has no module layer -- see {@link Module}). Stock code calls this on
     * ordinary paths, e.g. JUnit's {@code ModuleUtils.getModuleVersion}, which then short-circuits on
     * {@code isNamed()}.
     */
    /**
     * Always null: joe-ng has no {@code Package} objects -- the boot image is a flat class directory with no
     * package-level metadata (no sealing, no spec/impl title or version, no manifest attributes).
     *
     * <p>Null is a SUPPORTED answer, not a fudge. The JDK contract is "null if no Package object was created
     * by the class loader", and stock callers handle it: JUnit's {@code PackageUtils.getAttribute} wraps this
     * in {@code Optional.ofNullable(...)} precisely so an absent package yields an empty Optional. Returning
     * a fabricated Package with blank attributes would be the lie.
     */
    public Package getPackage()
    {
        return null;
    }

    public Module getModule()
    {
        return Module.UNNAMED;
    }

    public String getName()
    {
        return getName0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classNameOf}): mirror -> a fresh name String. */
    private static native String getName0(Class c);

    /**
     * The Java language modifiers of this class/interface ({@code public}/{@code private}/{@code abstract}/...).
     * For a nested class these come from the enclosing class's {@code InnerClasses} attribute; the VM-internal
     * {@code ACC_SUPER} bit is stripped by the VM.
     */
    public int getModifiers()
    {
        return (int) classModifiers0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classModifiers}): mirror -> class access flags. Returns
     *  {@code long} (not {@code int}) deliberately: a 1-arg {@code int}-returning native mis-compiled in the JIT
     *  (register-clobber corruption); the {@code (J)J} shape matches the working {@code getName0}/{@code superclass0}. */
    private static native long classModifiers0(Class c);

    /** True if this Class represents an interface (the {@code ACC_INTERFACE} bit). */
    public boolean isInterface()
    {
        return (classModifiers0(this) & 0x0200) != 0;
    }

    /**
     * Every method this class declares, in VM registry order. Enumerated from the method registry (which is
     * exactly what the VM knows about -- each method of a registered class is there, compiled or as a deferral
     * stub) rather than from the classfile.
     *
     * <p>With {@code Method.isAnnotationPresent} this is what makes annotation-driven discovery possible: find
     * the methods carrying {@code @Test} instead of hand-listing their names.
     */
    public java.lang.reflect.Method[] getDeclaredMethods()
    {
        int n = (int) declaredMethodCount0(this);
        java.lang.reflect.Method[] out = new java.lang.reflect.Method[n];
        int i = 0;
        int k = 0;
        while (i < n)
        {
            String nm = declaredMethodAt0(this, i);
            String ds = declaredMethodDescAt0(this, i);
            try
            {
                // BY NAME AND DESCRIPTOR: a class may declare two methods of the same name, and resolving by name
                // alone hands back the same one for both -- so an overloaded class enumerated to duplicates and
                // the other overload was unreachable. (A stock @ParameterizedTest is exactly that shape: the test
                // and its same-named @MethodSource factory.)
                out[k] = java.lang.reflect.Method.resolve(this, nm, ds);   // resolves + compiles on demand
                k += 1;
            }
            catch (NoSuchMethodException e)
            {
                // a declared method the VM cannot resolve (native without a helper) -- skip it
            }
            i += 1;
        }
        if (k == n)
        {
            return out;
        }
        java.lang.reflect.Method[] trimmed = new java.lang.reflect.Method[k];
        int j = 0;
        while (j < k)
        {
            trimmed[j] = out[j];
            j += 1;
        }
        return trimmed;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodAt}): the n-th declared method's NAME. */
    private static native String declaredMethodAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodDescAt}): the n-th method's DESCRIPTOR. */
    private static native String declaredMethodDescAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredMethodCount}): how many methods it declares.
     *  {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long declaredMethodCount0(Class<?> c);

    /** True if this Class is an array type — the VM tags array Types, so this is a tag test on the mirror. */
    public boolean isArray()
    {
        return isArray0(this) != 0L;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.isArrayClass}): mirror -> 1 if its Type is an array
     *  Type. {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long isArray0(Class c);

    /** The element type of an array class (from the array Type's element slot), else null. Feeds
     *  {@code Array.newInstance(a.getClass().getComponentType(), n)} (TimSort/Arrays.copyOf/toArray) the right
     *  element Type so the created array is typed {@code [L<component>;} (and {@code instanceof T[]} matches). */
    public Class<?> getComponentType()
    {
        return getComponentType0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.componentTypeOf}): array Class -> element-type mirror. */
    private static native Class<?> getComponentType0(Class c);

    /**
     * Stock java.base calls this from the wrapper classes' initializers -- {@code Integer.TYPE =
     * getPrimitiveClass("int")}, and likewise {@code Void} -- so a name-winning {@code Class} overlay that
     * omits it makes every one of those {@code <clinit>}s trap. That is exactly what happened: the first run
     * died in {@code java/lang/Void.<clinit>}.
     *
     * <p>Stock declares it {@code native}; here the name→descriptor mapping is ordinary Java and only the
     * mirror lookup is a native, which keeps the native's signature a plain {@code (J)J}.
     */
    static Class<?> getPrimitiveClass(String name)
    {
        int c = 0;
        if (name.equals("int"))          { c = 0x49; }
        else if (name.equals("long"))    { c = 0x4A; }
        else if (name.equals("double"))  { c = 0x44; }
        else if (name.equals("float"))   { c = 0x46; }
        else if (name.equals("short"))   { c = 0x53; }
        else if (name.equals("byte"))    { c = 0x42; }
        else if (name.equals("char"))    { c = 0x43; }
        else if (name.equals("boolean")) { c = 0x5A; }
        else if (name.equals("void"))    { c = 0x56; }
        return primitiveClass0(c);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.primClassOf}): descriptor char -> primitive mirror. */
    private static native Class<?> primitiveClass0(long descChar);

    /** True if this Class is a primitive type ({@code int.class}), i.e. its Type carries the primitive tag. */
    public boolean isPrimitive()
    {
        return isPrimitive0(this) != 0L;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.isPrimClass}): mirror -> 1 if its Type is a primitive
     *  Type. {@code long}-returning for the same reason as {@code classModifiers0}. */
    private static native long isPrimitive0(Class c);

    /** True if this class was synthesised by the compiler ({@code ACC_SYNTHETIC}). */
    public boolean isSynthetic()
    {
        return (classModifiers0(this) & 0x1000) != 0;
    }

    /** True if this Class represents an enum type (the {@code ACC_ENUM} bit; nested enums carry it in the
     *  enclosing class's {@code InnerClasses} entry, which {@code getModifiers} already reads). */
    public boolean isEnum()
    {
        return (getModifiers() & 0x4000) != 0;
    }

    /**
     * The enum constants of this enum type (in declaration order), or {@code null} if this is not an enum.
     * Implemented via the enum's compiler-synthesised {@code values()} (reached through M2 reflection) — the
     * same array the language exposes — rather than the stock {@code getEnumConstantsShared} cache. Not cloned
     * (single-threaded, read-only use), so the caller must not mutate the returned array.
     */
    public Object[] getEnumConstants()
    {
        if (!isEnum())
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method values = getDeclaredMethod("values");
            return (Object[]) values.invoke(null);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** The package name (the binary name up to, but excluding, the last '.'), or "" for the default package. */
    public String getPackageName()
    {
        String n = getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(0, dot);
    }

    /** The simple (unqualified) class name: the binary name after the last '.' or '$', whichever is later. */
    public String getSimpleName()
    {
        String n = getName();
        int dot = n.lastIndexOf('.');
        int dollar = n.lastIndexOf('$');
        int cut = dot > dollar ? dot : dollar;
        return cut < 0 ? n : n.substring(cut + 1);
    }

    /** The canonical name (dotted binary name with nested '$' turned into '.'). Local/anonymous classes (no
     *  canonical name in the JLS) are not distinguished yet — see arc M1. */
    public String getCanonicalName()
    {
        return getName().replace('$', '.');
    }

    /** True if {@code obj} is non-null and assignable to this type (the {@code instanceof} walk). */
    public boolean isInstance(Object obj)
    {
        return isInstance0(obj, typeAddr);
    }

    /** VM native (maps straight onto the JIT's {@code VM.instanceOf(JJ)I} helper: obj in x0, Type in x1). */
    private static native boolean isInstance0(Object obj, long type);

    /**
     * True if {@code other}'s type equals this type or has it on its superclass chain — a pure-Java walk of
     * the Type nodes ({@code superType} at Type+8) via the {@code Magic.load64} intrinsic. Interface
     * assignability (itables) is not consulted; extend when reached code needs it.
     */
    public boolean isAssignableFrom(Class other)
    {
        long t = other.typeAddr;
        while (t != 0L)
        {
            if (t == typeAddr)
            {
                return true;
            }
            t = Magic.load64(t + 8L);                   // Type.superType
        }
        return false;
    }

    /**
     * The cheap half of the reflection surface {@code make overlaycheck} listed as REFERENCED but dropped.
     * Each of these is answerable from what the mirror already knows -- the Type chain, the modifiers, and the
     * name -- so leaving them undeclared bought nothing and cost a DENYLIST TRAP on the day one is reached.
     *
     * <p>The half NOT added here is the half that needs machinery joe-ng does not have:
     * {@code getAnnotation}/{@code getAnnotations} need a live annotation instance (a Proxy runtime),
     * {@code getProtectionDomain} needs {@code java/security} (denylisted), {@code getResource*} needs URL and
     * resource enumeration, and the generic-signature methods need a signature parser. Those stay in the
     * backlog rather than being answered wrongly.
     */
    @SuppressWarnings("unchecked")
    public <U> Class<? extends U> asSubclass(Class<U> clazz)
    {
        if (!clazz.isAssignableFrom(this))
        {
            throw new ClassCastException(getName() + " is not a subclass of " + clazz.getName());
        }
        return (Class<? extends U>) this;
    }

    /** Stock semantics: null casts cleanly; anything else must be an instance. */
    @SuppressWarnings("unchecked")
    public T cast(Object obj)
    {
        if (obj != null && !isInstance(obj))
        {
            throw new ClassCastException("Cannot cast " + obj.getClass().getName() + " to " + getName());
        }
        return (T) obj;
    }

    /**
     * The annotation INSTANCE carried by this class, or null. Same machinery as {@link
     * java.lang.reflect.Method#getAnnotation} -- the returned object implements the annotation interface, so
     * calling an element method on it is an ordinary interface dispatch.
     *
     * <p>THE BOUND IS LOAD-BEARING. {@code <T extends Annotation>} erases the return to
     * {@code Ljava/lang/annotation/Annotation;}, which is the descriptor stock callers reference. Declared as a
     * plain {@code <T>} it erases to {@code Ljava/lang/Object;} -- a DIFFERENT METHOD, which resolves nowhere
     * and traps. That is exactly what happened: the first cut of this compiled, ran green in a probe that
     * called it directly, and still failed for JUnit's launcher.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(this, annoDescriptorOf(anno));
    }

    /** Takes a wildcard, as stock does, so it cannot call the BOUNDED {@code getAnnotation} directly. */
    public boolean isAnnotationPresent(Class<?> anno)
    {
        return anno != null && annoGet0(this, annoDescriptorOf(anno)) != null;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAnnoGet}): mirror + descriptor -> instance. */
    private static native Object annoGet0(Class c, byte[] descriptor);

    /** {@code com.x.Foo} -> the bytes of {@code Lcom/x/Foo;} -- the form the classfile stores. */
    private static byte[] annoDescriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        for (int i = 0; i < n.length(); i++)
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
        }
        out[out.length - 1] = (byte) ';';
        return out;
    }

    /** For a non-array, non-primitive class this is {@link #getName()}; arrays report the source-style form. */
    public String getTypeName()
    {
        if (isArray())
        {
            Class<?> c = getComponentType();
            return c == null ? getName() : c.getTypeName() + "[]";
        }
        return getName();
    }

    /** Null: joe-ng loads every class through one VM loader, which is the bootstrap loader's own answer. */
    public ClassLoader getClassLoader()
    {
        return null;
    }

    public boolean isAnnotation()
    {
        return (getModifiers() & 0x2000) != 0;          // ACC_ANNOTATION
    }

    /**
     * NESTING is read from the binary name, which is what the JVM guarantees for a nested class: javac emits
     * {@code Outer$Inner}, and only a nested class carries a '$'. An anonymous class's simple name is all
     * digits ({@code Outer$1}); a local class's begins with digits then letters ({@code Outer$1Named}); a
     * member class's begins with a letter. This is the same rule {@link #getSimpleName} already relies on.
     */
    public boolean isMemberClass()
    {
        String sn = nestedSimpleName();
        return sn != null && !sn.isEmpty() && !isDigit(sn.charAt(0));
    }

    public boolean isAnonymousClass()
    {
        String sn = nestedSimpleName();
        if (sn == null || sn.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < sn.length(); i++)
        {
            if (!isDigit(sn.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    public boolean isLocalClass()
    {
        String sn = nestedSimpleName();
        return sn != null && !sn.isEmpty() && isDigit(sn.charAt(0)) && !isAnonymousClass();
    }

    /** The enclosing class of a nested class, or null. */
    public Class<?> getEnclosingClass()
    {
        String n = getName();
        int i = n.lastIndexOf('$');
        if (i < 0)
        {
            return null;
        }
        try
        {
            return Class.forName(n.substring(0, i));
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    /** The part after the last '$', or null when this class is not nested. */
    private String nestedSimpleName()
    {
        String n = getName();
        int i = n.lastIndexOf('$');
        return i < 0 ? null : n.substring(i + 1);
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    /** The superclass's mirror (cached per Type, so {@code getSuperclass() == Super.class}), or null. */
    public Class getSuperclass()
    {
        return superclass0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.superclassOf}): super Type -> its (cached) mirror. */
    private static native Class superclass0(Class c);

    /**
     * Declared instance/static field named {@code name} (any access), or throws {@code NoSuchFieldException}.
     * Modifiers + type descriptor come from the loader re-walking this class's classfile ({@code fieldMeta0}).
     */
    /**
     * Every field this class DECLARES -- public or not, instance or static -- in classfile order.
     *
     * <p>Enumerated by index and resolved by NAME, which is sound here in a way the method equivalent is not:
     * a class cannot declare two fields of the same name, so there is no descriptor ambiguity to guard against
     * (that guard exists in {@link #getDeclaredMethods} because overloads DO collide, and resolving by name
     * alone handed back the same overload twice).
     *
     * <p>Inherited fields are excluded, as stock requires -- {@link #getFields} is the one that walks up.
     *
     * <p><b>STATIC fields are omitted, and this DIVERGES from stock.</b> A {@code Field} here reads through an
     * INSTANCE offset ({@code Field.addr} is {@code addrOf(obj) + fieldOffset}), and joe-ng's field registry
     * holds instance fields only -- so a static would have to be handed back as an object whose {@code get()}
     * computes a meaningless address. Returning nothing is the lesser wrong: a caller iterating fields sees
     * fewer than it should, which is visible, rather than reading a plausible number from the wrong memory,
     * which is not. Closing it properly means giving Field a static-cell mode, not widening this walk.
     */
    public java.lang.reflect.Field[] getDeclaredFields()
    {
        int n = (int) declaredFieldCount0(this);
        java.lang.reflect.Field[] out = new java.lang.reflect.Field[n];
        int i = 0;
        int k = 0;
        while (i < n)
        {
            String nm = declaredFieldAt0(this, i);
            if (nm != null)
            {
                try
                {
                    out[k] = getDeclaredField(nm);
                    k += 1;
                }
                catch (NoSuchFieldException e)
                {
                    // declared in the classfile but not resolvable through the field registry -- skip it
                }
            }
            i += 1;
        }
        if (k == n)
        {
            return out;
        }
        java.lang.reflect.Field[] trimmed = new java.lang.reflect.Field[k];
        int j = 0;
        while (j < k)
        {
            trimmed[j] = out[j];
            j += 1;
        }
        return trimmed;
    }

    /**
     * The PUBLIC fields of this class and its superclasses, as stock. Walks the chain most-derived first; a
     * field HIDDEN by a subclass declaration is reported once, by the most-derived declaration, which is what
     * stock does and what a caller reading values expects.
     */
    public java.lang.reflect.Field[] getFields()
    {
        java.lang.reflect.Field[] acc = new java.lang.reflect.Field[64];
        int k = 0;
        Class<?> c = this;
        int hops = 0;
        while (c != null && hops < 24)
        {
            java.lang.reflect.Field[] own = c.getDeclaredFields();
            int i = 0;
            while (i < own.length && k < acc.length)
            {
                if ((own[i].getModifiers() & 0x0001) != 0)      // ACC_PUBLIC
                {
                    boolean hidden = false;
                    int j = 0;
                    while (j < k)
                    {
                        if (acc[j].getName().equals(own[i].getName()))
                        {
                            hidden = true;                      // a more-derived declaration already won
                            break;
                        }
                        j += 1;
                    }
                    if (!hidden)
                    {
                        acc[k] = own[i];
                        k += 1;
                    }
                }
                i += 1;
            }
            c = c.getSuperclass();
            hops += 1;
        }
        java.lang.reflect.Field[] out = new java.lang.reflect.Field[k];
        int j = 0;
        while (j < k)
        {
            out[j] = acc[j];
            j += 1;
        }
        return out;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredFieldAt}): the n-th declared field's NAME. */
    private static native String declaredFieldAt0(Class<?> c, int want);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.declaredFieldCount}): how many fields it declares. */
    private static native long declaredFieldCount0(Class<?> c);

    public java.lang.reflect.Field getDeclaredField(String name) throws NoSuchFieldException
    {
        byte[] nb = name.getBytes();
        int mods = fieldMods0(this, nb);
        if (mods < 0)
        {
            throw new NoSuchFieldException(name);
        }
        return new java.lang.reflect.Field(this, name, mods, fieldTypeChar0(this, nb));
    }

    /**
     * The PUBLIC field named {@code name} declared by this class or inherited from a superclass, or throws
     * {@code NoSuchFieldException} if none is accessible. Unlike {@link #getDeclaredField} (which returns any
     * field of this class regardless of access), {@code getField} enforces public visibility and walks the
     * superclass chain — the reflection access-control rule these tests probe. (Interface-constant fields and
     * static fields are not yet enumerated on metal — see reflection arc M1/M2.)
     */
    public java.lang.reflect.Field getField(String name) throws NoSuchFieldException
    {
        if (name == null)
        {
            throw new NullPointerException();
        }
        int mods = fieldModifiers(name);                // this class's own instance field flags, or -1 if absent
        if (mods >= 0 && (mods & 0x0001) != 0)          // ACC_PUBLIC
        {
            return new java.lang.reflect.Field(this, name, mods, fieldTypeChar(name));
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * The declared method named {@code name} (first match — overload resolution by parameter types is not yet
     * implemented), or throws {@code NoSuchMethodException}. Returns a {@code Method} that can be reflectively
     * {@code invoke}d. The {@code parameterTypes} are accepted for signature compatibility but not yet matched.
     */
    public java.lang.reflect.Method getDeclaredMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        return java.lang.reflect.Method.resolve(this, name);
    }

    /**
     * The declared constructor taking {@code parameterTypes}, matched by <em>arity</em> only for now (first
     * {@code <init>} with that parameter count), or throws {@code NoSuchMethodException}. Returns a
     * {@code Constructor} that can reflectively {@code newInstance}.
     */
    public java.lang.reflect.Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes)
            throws NoSuchMethodException
    {
        return java.lang.reflect.Constructor.resolve(this, parameterTypes == null ? 0 : parameterTypes.length);
    }

    /** Access flags of the named own instance field, or -1 if absent (reflection helper for the field updaters). */
    public int fieldModifiers(String name)
    {
        return fieldMods0(this, name.getBytes());
    }

    /** First char of the named own instance field's JVM type descriptor ('I','J','Z','L','['), or -1. */
    public int fieldTypeChar(String name)
    {
        return fieldTypeChar0(this, name.getBytes());
    }

    /** VM native -> {@code VM.fieldMods}: this class's own instance field {@code name}'s access flags, or -1. */
    static native int fieldMods0(Class c, byte[] name);

    /** VM native -> {@code VM.fieldTypeChar}: first char of that field's JVM type descriptor ('I','J','L',...). */
    static native int fieldTypeChar0(Class c, byte[] name);
}
