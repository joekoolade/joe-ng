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
public final class Class<T> implements java.lang.reflect.Type
{
    private long typeAddr;      // the VM Type node this Class mirrors (set by the VM at materialisation)

    /**
     * Per-Class map of {@link ClassValue} entries, declared exactly as JDK 26 does
     * ({@code transient ClassValue.ClassValueMap classValueMap;}, Class.java:3717).
     *
     * <p>{@code ClassValue.get} reads and writes this field on the Class it is keyed by; without it the
     * access resolved NOWHERE and the VM aliased it to SLOT 0 -- i.e. it read and wrote {@code typeAddr},
     * the Type pointer every Class native dereferences. The loader said so
     * ({@code UNRESOLVED FIELD (aliases slot 0): java/lang/Class.classValueMap}) and the corruption surfaced
     * as an NPE inside the VM's own dispatch resolver, a long way from here.
     *
     * <p>ADDING IT REQUIRED WIDENING THE MIRROR: {@code Loader.classMirror} allocates Class objects itself,
     * and its size was header(16) + one field. A declaration without that change writes past the object.
     */
    transient ClassValue.ClassValueMap classValueMap;

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
    /**
     * Every PUBLIC method of this class: its own, those inherited from superclasses, and those reachable
     * through its interfaces (including defaults). Deduplicated by name AND descriptor.
     *
     * <p>The key has to be built HERE rather than from a {@link java.lang.reflect.Method}, because a Method
     * keeps only the first character of each parameter type -- enough to marshal a call, not enough to tell
     * two overloads apart. The enumeration natives hand back the full descriptor, so the dedupe happens
     * before the Method is ever constructed.
     *
     * <p>A subclass method wins over the superclass method it overrides simply by being seen first, which is
     * what walking the chain most-derived-first gives.
     *
     * <p>Constructors and {@code <clinit>} are excluded, as stock does -- the VM's method enumeration is the
     * registry's view and lists them, so they are filtered by name.
     */
    public java.lang.reflect.Method[] getMethods()
    {
        java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
        java.util.ArrayList<java.lang.reflect.Method> out =
                new java.util.ArrayList<java.lang.reflect.Method>();
        Class<?> c = this;
        while (c != null)
        {
            collectPublicMethods(c, seen, out);
            c = c.getSuperclass();
        }
        collectInterfaceMethods(this, seen, out);
        java.lang.reflect.Method[] arr = new java.lang.reflect.Method[out.size()];
        int i = 0;
        while (i < arr.length)
        {
            arr[i] = out.get(i);
            i += 1;
        }
        return arr;
    }

    /** Append {@code c}'s own public methods that no more-derived class has already contributed. */
    private static void collectPublicMethods(Class<?> c, java.util.ArrayList<String> seen,
            java.util.ArrayList<java.lang.reflect.Method> out)
    {
        int n = (int) declaredMethodCount0(c);
        int i = 0;
        while (i < n)
        {
            String nm = declaredMethodAt0(c, i);
            String ds = declaredMethodDescAt0(c, i);
            if (nm != null && ds != null && nm.length() > 0 && nm.charAt(0) != '<')
            {
                String key = nm + ds;
                if (!seen.contains(key))
                {
                    try
                    {
                        java.lang.reflect.Method m = java.lang.reflect.Method.resolve(c, nm, ds);
                        if (java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                        {
                            seen.add(key);
                            out.add(m);
                        }
                    }
                    catch (NoSuchMethodException e)
                    {
                        // a declared method the VM cannot resolve (a native with no helper) -- skip it, as
                        // getDeclaredMethods does
                    }
                }
            }
            i += 1;
        }
    }

    /** Append the public methods of {@code c}'s interfaces, transitively -- where DEFAULT methods live. */
    private static void collectInterfaceMethods(Class<?> c, java.util.ArrayList<String> seen,
            java.util.ArrayList<java.lang.reflect.Method> out)
    {
        Class<?> k = c;
        while (k != null)
        {
            Class<?>[] ifs = k.getInterfaces();
            int i = 0;
            while (i < ifs.length)
            {
                collectPublicMethods(ifs[i], seen, out);
                collectInterfaceMethods(ifs[i], seen, out);   // super-interfaces
                i += 1;
            }
            k = k.getSuperclass();
        }
    }

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
     * Is this a record class? Answered by its SUPERCLASS being {@code java.lang.Record}, which is the check
     * stock makes first (Class.java:3386) before its intrinsified fast path.
     *
     * <p>The FINAL-modifier and {@code isRecord0()} halves of stock's test are deliberately not reproduced:
     * the superclass check is decisive on its own here, because {@code java.lang.Record} is abstract and the
     * compiler is the only thing that may extend it -- JLS 8.10 forbids a class declaring
     * {@code extends Record} directly. Stock's own comment says as much: "this superclass and final modifier
     * check is not strictly necessary".
     *
     * <p>Reached from {@code java/io/ObjectStreamClass.<init>}, which is how serialization describes any
     * class at all; without it that constructor resolved nowhere and surfaced as a trap with an EMPTY callee
     * and {@code TRAPWIRE index=-1} -- the signature of a LATE-RESOLUTION failure rather than a denial.
     */
    public boolean isRecord()
    {
        Class<?> sup = getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getName());
    }

    /**
     * The enum constants of this enum type (in declaration order), or {@code null} if this is not an enum.
     * Implemented via the enum's compiler-synthesised {@code values()} (reached through M2 reflection) — the
     * same array the language exposes — rather than the stock {@code getEnumConstantsShared} cache. Not cloned
     * (single-threaded, read-only use), so the caller must not mutate the returned array.
     */
    /**
     * name -> constant, for {@code Enum.valueOf(Class, String)}.
     *
     * <p>Package-private and returning a raw {@code Map}, matching stock, because {@code Enum.valueOf} calls it
     * directly and both live in {@code java.lang}. Built from {@link #getEnumConstants()}, which reaches the
     * enum's own {@code values()} -- so the constants here are the SAME objects the rest of the VM holds, and
     * {@code valueOf(E.class, "X") == E.X} as identity, not merely as equals.
     *
     * <p>NOT CACHED, unlike stock's volatile field. Caching would mean a new field on {@code Class}, whose
     * mirrors the VM allocates itself; rebuilding costs one reflective {@code values()} call on a path
     * ({@code Enum.valueOf}) that is not hot, and avoids putting VM-allocated objects and guest field layout in
     * the same argument.
     */
    java.util.Map enumConstantDirectory()
    {
        java.util.HashMap m = new java.util.HashMap();
        Object[] constants = getEnumConstants();
        if (constants == null)
        {
            return m;                                   // not an enum, or values() unreachable: empty, not null
        }
        for (int i = 0; i < constants.length; i++)
        {
            Object c = constants[i];
            if (c != null)
            {
                m.put(((Enum) c).name(), c);
            }
        }
        return m;
    }

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
    /**
     * Is a value of {@code other} assignable to this type?
     *
     * <p>THIS USED TO WALK {@code Type.superType} AND NOTHING ELSE -- the SUPERCLASS chain -- so every
     * INTERFACE answer was false: {@code Collection.class.isAssignableFrom(List.class)} said no, and so did
     * {@code List.class.isAssignableFrom(ArrayList.class)}. The demo suite never caught it because the arm it
     * asserts, {@code Number.isAssignableFrom(Integer)}, is a class-chain question that always worked.
     *
     * <p>picocli's {@code isMultiValue()} IS this call: {@code Collection.class.isAssignableFrom(
     * field.getType())}. Answering false meant a {@code List<ClassSelector>} option was treated as
     * single-valued, so a bare ClassSelector was stored into the List field -- and JUnit's
     * {@code getExplicitSelectors} then called {@code addAll} on it, whose first act is {@code toArray()}.
     *
     * <p>It asks the VM now. {@code VM.typeAssignable} has always been the real rule: itable directories for
     * interfaces, array covariance, and an O(1) display check for class targets. Keeping a second, weaker
     * copy of that rule here bought nothing.
     */
    public boolean isAssignableFrom(Class other)
    {
        if (other == null)
        {
            throw new NullPointerException();
        }
        return assignable0(other.typeAddr, typeAddr);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.assignable} -> {@code VM.typeAssignable}). */
    private static native boolean assignable0(long fromType, long targetType);

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

    /**
     * The annotation DECLARED DIRECTLY on this class, or null.
     *
     * <p>In stock this differs from {@link #getAnnotation} by {@code @Inherited} alone: getAnnotation also
     * finds an {@code @Inherited} annotation on a SUPERCLASS. The native here reads THIS class's own
     * {@code RuntimeVisibleAnnotations} and nothing else, so the two coincide -- which makes THIS method
     * exact and {@code getAnnotation} the one that diverges. Stated rather than hidden: an {@code @Inherited}
     * annotation on a superclass is found by neither, and nothing reached so far asks for one.
     *
     * <p>THE BOUND IS LOAD-BEARING, for the reason spelled out on {@link #getAnnotation}: it erases the
     * return to {@code Ljava/lang/annotation/Annotation;}, which is the descriptor stock callers reference.
     * JUnit reaches this through {@code AnnotatedElement}, whose method it is -- and this class does not
     * declare that interface (recorded in the overlay baseline), so the call arrives by LATE dispatch against
     * the receiver's class and only resolves if the name AND descriptor match exactly.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getDeclaredAnnotation(Class<T> anno)
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

    /**
     * Every annotation DECLARED DIRECTLY on this class. Never null; empty when there are none.
     *
     * <p>This is what JUnit's {@code AnnotationUtils.findAnnotation} walks to find META-ANNOTATIONS -- an
     * annotation carried by another annotation -- so an empty answer would not fail, it would silently report
     * that nothing is annotated.
     *
     * <p>A FRESH array each call, as stock specifies ("the caller of this method is free to modify the
     * returned array"). An annotation whose interface is not loaded is omitted and named on the console by
     * the VM, rather than appearing as a null element.
     */
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        java.lang.annotation.Annotation[] a = annoAll0(this);
        return a == null ? new java.lang.annotation.Annotation[0] : a;
    }

    /**
     * As {@link #getDeclaredAnnotations}. In stock these differ by {@code @Inherited} alone -- getAnnotations
     * also reports an {@code @Inherited} annotation carried by a SUPERCLASS -- and this VM does not implement
     * that, so the two coincide. Stated rather than hidden, and the same divergence {@code getAnnotation}
     * already carries.
     */
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAnnoGet}): mirror + descriptor -> instance. */
    private static native Object annoGet0(Class c, byte[] descriptor);

    /**
     * The classes and interfaces declared as MEMBERS of this class -- public, protected, package and private
     * alike -- excluding inherited ones. Never null; empty when there are none.
     *
     * <p>Read from the classfile's {@code InnerClasses} attribute, not from the binary name. The nesting
     * PREDICATES above work from the name because they ask "is THIS class a member", which {@code Outer$Inner}
     * answers; this asks the reverse, "which classes are members of this one", and no name can answer that.
     *
     * <p>LOCAL AND ANONYMOUS CLASSES ARE EXCLUDED, as stock excludes them: JVMS 4.7.6 requires a zero
     * {@code outer_class_info_index} for both, which is exactly the field the VM filters on.
     *
     * <p>Getting these mirrors does NOT initialize the classes -- obtaining a Class is not an active use
     * (JVMS 5.5). A member class this VM cannot load is omitted and named on the console rather than returned
     * as a null element.
     */
    public Class<?>[] getDeclaredClasses()
    {
        Class<?>[] a = declaredClasses0(this);
        return a == null ? new Class<?>[0] : a;
    }

    /**
     * The PUBLIC member classes of this class, including those inherited from superclasses and
     * superinterfaces. Never null.
     *
     * <p>Landed beside {@link #getDeclaredClasses} deliberately: a member a name-winning overlay does not
     * declare CEASES TO EXIST, and shipping one half of a pair is how that trap has cost this project a boot
     * ten times over. Nothing reached so far calls it -- {@code ReflectionUtils} uses the declared form -- so
     * it is here to be present and correct rather than because something waits on it.
     *
     * <p>Walks the superclass chain and the interfaces, most-derived first, and de-duplicates by name: a
     * class and its superclass may both report the same inherited member.
     */
    public Class<?>[] getClasses()
    {
        java.util.ArrayList<Class<?>> out = new java.util.ArrayList<Class<?>>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Class<?> c = this;
        while (c != null)
        {
            collectPublicClasses(c, out, seen);
            c = c.getSuperclass();
        }
        collectInterfaceClasses(this, out, seen);
        return out.toArray(new Class<?>[0]);
    }

    /**
     * Public member classes of {@code c}'s interfaces, TRANSITIVELY -- a super-interface's members are
     * inherited too, so a walk of the DIRECT interfaces alone misses them. Same shape as
     * {@link #collectInterfaceMethods}, deliberately: two walks over the same relation that disagreed about
     * transitivity would be the kind of half-correct member this overlay keeps getting bitten by.
     */
    private static void collectInterfaceClasses(Class<?> c, java.util.ArrayList<Class<?>> out,
            java.util.HashSet<String> seen)
    {
        Class<?> k = c;
        while (k != null)
        {
            Class<?>[] ifs = k.getInterfaces();
            for (int i = 0; i < ifs.length; i++)
            {
                collectPublicClasses(ifs[i], out, seen);
                collectInterfaceClasses(ifs[i], out, seen);   // super-interfaces
            }
            k = k.getSuperclass();
        }
    }

    /** Adds {@code c}'s public member classes that have not been seen; a member of an interface is public. */
    private static void collectPublicClasses(Class<?> c, java.util.ArrayList<Class<?>> out,
            java.util.HashSet<String> seen)
    {
        Class<?>[] d = c.getDeclaredClasses();
        for (int i = 0; i < d.length; i++)
        {
            if (java.lang.reflect.Modifier.isPublic(d[i].getModifiers()) && seen.add(d[i].getName()))
            {
                out.add(d[i]);
            }
        }
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classDeclClasses}): mirror -> Class[]. */
    private static native Class<?>[] declaredClasses0(Class c);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classAnnoAll}): mirror -> Annotation[]. */
    private static native java.lang.annotation.Annotation[] annoAll0(Class c);

    /**
     * The interfaces this class DIRECTLY declares, in declaration order; empty when it declares none.
     *
     * <p>Declared, not the transitive closure -- which is what stock returns, and what JUnit's
     * {@code findAnnotation} needs, since it recurses into each interface itself.
     */
    public Class<?>[] getInterfaces()
    {
        Class<?>[] a = interfaces0(this);
        return a == null ? new Class<?>[0] : a;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.classIfaces}): mirror -> Class[]. */
    private static native Class<?>[] interfaces0(Class c);

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

    /**
     * {@code newInstance()} -- the no-arg construction stock deprecated in favour of
     * {@code getDeclaredConstructor().newInstance()}, which is exactly what it delegates to here.
     *
     * <p>Stock declares the checked {@code InstantiationException}/{@code IllegalAccessException} and this
     * keeps them, so a caller's existing catch blocks still compile and still run: picocli's
     * {@code DefaultFactory.create} catches around it and falls back, and swallowing the failure here would
     * turn its fallback into dead code.
     */
    @SuppressWarnings("unchecked")
    public T newInstance() throws InstantiationException, IllegalAccessException
    {
        try
        {
            return (T) getDeclaredConstructor().newInstance();
        }
        catch (NoSuchMethodException e)
        {
            throw new InstantiationException(getName() + " has no no-arg constructor");
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            throw new InstantiationException(getName() + " constructor threw");
        }
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
