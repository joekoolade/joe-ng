package vm;

import board.bcm2711.Uart;
import magic.Magic;
import objectmodel.ObjectModel;
import static vm.VM.*;   // strBytes/printStr/heapBytes helpers + fileDir/fileCount/taskThreadObj/curTask fields

/**
 * The guest-{@code native} implementations extracted from VM.java (which had grown past 4000 lines): java.base
 * (time, {@code System.arraycopy}, reflection: forName/defineClass/Method/Constructor/Field/Array), the
 * java.net/sun.nio.ch sockets over {@code net.Tcp}, VarHandle field-offset resolution, and
 * {@code Throwable.printStackTrace0}. Each is reached from JIT'd guest code via {@code Loader.nativeBuf} ->
 * the {@code VM.<name>Addr} static, which STAYS in VM (filled by the writer's {@code stashHelper}, now resolving
 * {@code vm/VMNatives.<name>}). Grouping them here keeps VM.java to its core (boot, scheduler, GC, unwind,
 * class-loading glue); the only in-VM references are the force-compile roots, which call {@code VMNatives.<name>}.
 * {@code instanceOf} deliberately stayed in VM -- it is also the checkcast/instanceof/unwind JIT helper.
 */
final class VMNatives
{
    /**
     * {@code java/lang/System.nanoTime()} — a monotonic clock in ns, from the ARM generic timer. Scaled
     * multiply-FIRST (via a seconds/remainder split so {@code ticks*1e9} can't overflow): dividing
     * {@code 1e9/freq} first truncates badly at non-power-of-two frequencies (e.g. a real Pi 4's 54 MHz ->
     * 18 instead of 18.52, ~2.8% slow, so a 1 ms sleep mis-measures as 0 ms).
     */
    static long nanoTime()
    {
        long ticks = Magic.readCNTPCT_EL0();
        long freq = Magic.readCNTFRQ_EL0();
        return ticks / freq * 1000000000L + ticks % freq * 1000000000L / freq;
    }

    /** {@code java/lang/System.currentTimeMillis()} — ms since boot (no wall clock on bare metal). */
    static long currentTimeMillis()
    {
        return Magic.readCNTPCT_EL0() / (Magic.readCNTFRQ_EL0() / 1000L);
    }

    /**
     * Identity native for the *ToRawBits / bitsTo* conversions ({@code Float.floatToRawIntBits},
     * {@code Float.intBitsToFloat}, {@code Double.doubleToRawLongBits}, {@code Double.longBitsToDouble}):
     * joe-ng already holds floats/doubles as raw bits in GP registers, so these are pass-throughs.
     */
    static long identity(long x)
    {
        return x;
    }

    /**
     * {@code java/lang/System.arraycopy(src, srcPos, dst, dstPos, len)} — the most-used java.base native.
     * The element size comes from the source array's header (TIB slot), so it's generic over element type;
     * the copy is byte-wise and overlap-safe (like {@code memmove}, as {@code arraycopy} requires).
     */
    /** Element size (bytes) of an array: a raw array keeps it in @0; a typed array keeps it in its array Type's tag. */
    static long elemSize(long arr)
    {
        long tib = Magic.load64(arr + 0L);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return tib;                                    // raw array: @0 is the element size
        }
        return Magic.load64(Magic.load64(tib)) & 0xFFFFL;  // typed: arr@0=TIB, TIB[0]=Type, Type[0]=tag|elemSize
    }

    static void arraycopy(long src, int srcPos, long dst, int dstPos, int len)
    {
        long es = elemSize(src);                            // element size (bytes), raw header or typed array Type
        long n = (long) len * es;
        long from = src + 24L + (long) srcPos * es;        // ARRAY_BASE_OFFSET = 24
        long to = dst + 24L + (long) dstPos * es;
        if (to <= from)                                    // forward copy (no clobber when dst <= src)
        {
            long i = 0L;
            while (i < n)
            {
                Magic.store8(to + i, (byte) Magic.load8(from + i));
                i = i + 1L;
            }
        }
        else                                               // backward copy (overlap: dst after src)
        {
            long i = n;
            while (i > 0L)
            {
                i = i - 1L;
                Magic.store8(to + i, (byte) Magic.load8(from + i));
            }
        }
    }

    /**
     * EL1 exception handler (reached by a branch from every vector entry): print the syndrome,
     * faulting PC and fault address, then park. Does not return — this is a last-resort report.
     */
    /**
     * {@code Throwable.printStackTrace0()} native (self in x0): print the throwable's class + the frames captured
     * into its inline backtrace (bt0..bt7 @ self+16) by {@link #unwind} at throw time. Names each frame's method
     * via {@link Loader#printFrameAt} (demand-compiled methods / {@code <clinit>}s; image code shows "image/native").
     */
    /**
     * {@code [T.clone()} intrinsic: a shallow copy of any array. Copies the whole block body (length word +
     * elements, from the status-word size — every allocation records it) and carries the TIB over, so raw
     * (elem-size) and typed (array-Type TIB) arrays clone alike. Element values copy verbatim (shallow).
     */
    static long arrayClone(long ref)
    {
        if (ref <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        long size = Magic.load64(ref + 8L) & -8L;          // block size from the status word
        long copy = Heap.alloc((int) size);
        Magic.store64(copy, Magic.load64(ref));            // same TIB (raw elem size or typed array TIB)
        long i = 16L;
        while (i < size)
        {
            Magic.store64(copy + i, Magic.load64(ref + i));
            i += 8L;
        }
        return copy;
    }

    /**
     * {@code java.lang.reflect.Array.newInstance0(Class, int)} native: a {@code length}-element reference array
     * (8-byte elements) TYPED as {@code [L<component>;} — its TIB is {@code Loader.refArrayTib(componentType)},
     * the SAME interned array-TIB that {@code new component[]} / {@code instanceof component[]} use, so a caller
     * that {@code instanceof}-checks the result (e.g. {@code toArray(T[])} tests) matches. Backs the temp/work
     * arrays TimSort/ComparableTimSort/Arrays.copyOf allocate reflectively. Falls back to an untyped raw array
     * if the component's Type isn't resolvable (still fine for fill-and-return uses).
     */
    static long newReflectArray(long componentMirror, long length)
    {
        if (length < 0L)
        {
            return 0L;                                     // boot force-compile passes 0; guest checks negative first
        }
        long arr = Heap.allocArray((int) length, 8);       // 8-byte reference elements (raw header first)
        if (componentMirror > 0x1000L)
        {
            long compType = Magic.load64(componentMirror + 16L);   // Class mirror -> its Type (@16)
            if (compType != 0L)
            {
                Magic.store64(arr, Loader.refArrayTib(compType));  // typed [L<component>; TIB (interned per element)
            }
        }
        return arr;
    }

    /**
     * {@code Class.getComponentType0(Class)} native: for an array Class, the Class mirror of its element type
     * (read from the array Type's element slot); for a non-array Class, 0. Lets
     * {@code a.getClass().getComponentType()} feed {@link #newReflectArray} the right element Type.
     */
    static long componentTypeOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        long type = Magic.load64(mirror + 16L);            // mirror -> Type (@16)
        if (type == 0L)
        {
            return 0L;
        }
        long instSize = Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET);
        if ((instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) != ObjectModel.ARRAY_TYPE_TAG)
        {
            return 0L;                                     // not an array Type
        }
        long elemType = Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
        return elemType == 0L ? 0L : Loader.classMirror(elemType);   // primitive-element arrays have 0 elem Type
    }

    /**
     * {@code Class.isArray0(Class)} native: 1 if the mirror's Type is an array Type, else 0. Returns
     * {@code long} rather than {@code int} for the same reason {@code classModifiers0} does -- a 1-arg
     * int-returning native mis-compiles in the JIT, and {@code (J)J} is the shape known to work.
     */
    static long isArrayClass(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        return Loader.isArrayType(Magic.load64(mirror + 16L)) ? 1L : 0L;
    }

    /**
     * Late virtual dispatch: resolve call site {@code idx} against the receiver's dynamic type. Called only
     * from {@code Loader.virtualTramp}, which has already saved the argument registers.
     */
    static long virtualResolve(long recv, long idx)
    {
        return Loader.virtualResolve(recv, (int) idx);
    }

    /** {@code Throwable.stackTrace0(Throwable)} native: its inline backtrace as a StackTraceElement[]. */
    static long throwableTrace(long exc)
    {
        return Loader.traceFromThrowable(exc);
    }

    /**
     * {@code Class.declaredMethodAt0(Class, int)} native: the NAME of the n-th method the class declares, as a
     * guest String. A negative {@code want} returns the COUNT instead, so one native serves both.
     */
    static long declaredMethodAt(long mirror, long want)
    {
        return Loader.declaredMethodName(mirror, (int) want);
    }

    /** {@code Class.declaredMethodCount0(Class)} native: how many methods the class declares. */
    static long declaredMethodCount(long mirror)
    {
        return Loader.declaredMethodName(mirror, -1);
    }

    /**
     * {@code Method.annoPresent0(int, byte[])} native: 1 if the method registered at {@code rgIndex} carries
     * the annotation whose descriptor the byte[] holds. Marker level -- presence only, no element values.
     */
    static long annoPresent(long rgIndex, long descArr)
    {
        if (descArr <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        int n = (int) Magic.load64(descArr + 16L);         // byte[] length @16
        return Loader.methodAnnoPresent((int) rgIndex, descArr, n) ? 1L : 0L;
    }

    /**
     * {@code Class.primitiveClass0(long)} native: JVMS descriptor char -> the primitive {@code Class} mirror.
     * Backs {@code Class.getPrimitiveClass}, which stock wrapper initializers call for {@code Integer.TYPE}.
     */
    static long primClassOf(long descChar)
    {
        return Loader.primitiveMirror((int) descChar);
    }

    /**
     * {@code Class.isPrimitive0(Class)} native: 1 if the mirror's Type is a primitive Type, else 0. Same
     * {@code (J)J} shape as {@link #isArrayClass} and for the same JIT reason.
     */
    static long isPrimClass(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        return Loader.isPrimitiveType(Magic.load64(mirror + 16L)) ? 1L : 0L;
    }

    /**
     * M4: {@code Class.getName0(Class)} native — the mirror's Type ({@code @16}) -> a fresh guest String of
     * the class's dotted binary name (built by {@code Loader.classNameString} from the registry name bytes).
     */
    static long classNameOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        return Loader.classNameString(Magic.load64(mirror + 16L));
    }

    /**
     * Reflection: {@code Class.forName0(byte[])} native — resolve a binary class name (raw ASCII, dots) to its
     * Class mirror, incrementally loading the class into the live program if needed. 0 => guest throws
     * {@code ClassNotFoundException}. Boot force-compile passes a 0 array (guarded in {@code forNameMirror}).
     */
    static long forName(long nameArr)
    {
        return Loader.forNameMirror(nameArr);
    }

    /**
     * Reflection M3: {@code ClassLoader.defineClass0(name, byte[], off, len)} native — materialize a class from
     * the SUPPLIED classfile bytes into the live program and return its Class mirror. 0 => the guest throws
     * {@code ClassFormatError}/returns null. The {@code name} arg is advisory (the loader uses the classfile's
     * own this_class); boot force-compile passes a 0 array (guarded in {@code defineFromBytes}).
     */
    static long defineClass(long nameArr, long byteArr, long off, long len)
    {
        long type = Loader.defineFromBytes(byteArr, (int) off, (int) len);
        return type == 0L ? 0L : Loader.classMirror(type);
    }

    /**
     * Reflection: {@code Class.getModifiers()} native — the class's Java language modifiers. For a nested class
     * these come from the enclosing class's {@code InnerClasses} attribute (so a {@code private} inner reports
     * {@code private}); the VM-internal {@code ACC_SUPER} (0x20) bit is stripped either way.
     */
    static long classModifiers(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;                                     // boot force-compile passes 0
        }
        return (long) Loader.classModifiersOf(Magic.load64(mirror + 16L));
    }

    /** Reflection: {@code Method.methodResolve0(Class,byte[])} -> method-registry index of the named method, or -1. */
    static int methodResolve(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;                                     // boot force-compile passes 0
        }
        return Loader.methodResolve(Magic.load64(mirrorRef + 16L), nameArrRef);
    }

    /** Reflection: {@code Method.methodInfo0(int,byte[],long[])} -> fills param chars + {buf,access,retChar}; count. */
    static int methodInfo(long rgIndex, long paramCharsRef, long outRef)
    {
        if (paramCharsRef <= 0x1000L || outRef <= 0x1000L)
        {
            return 0;                                      // boot force-compile passes 0
        }
        return Loader.methodInfo((int) rgIndex, paramCharsRef, outRef);
    }

    /** Reflection: {@code Constructor.ctorResolve0(Class,int)} -> registry index of the <init> with that arity, or -1. */
    static int constructorResolve(long mirrorRef, long paramCount)
    {
        if (mirrorRef <= 0x1000L)
        {
            return -1;                                     // boot force-compile passes 0
        }
        return Loader.constructorResolve(Magic.load64(mirrorRef + 16L), (int) paramCount);
    }

    /** Reflection: {@code Constructor.allocInstance0(Class)} -> a fresh zeroed instance (TIB set), or 0. */
    static long allocInstance(long mirrorRef)
    {
        if (mirrorRef <= 0x1000L)
        {
            return 0L;                                     // boot force-compile passes 0
        }
        return Loader.allocInstance(Magic.load64(mirrorRef + 16L));
    }

    /**
     * M4: {@code Class.superclass0(Class)} native — the mirror's Type's {@code superType} ({@code @8}) ->
     * its (cached) mirror, or 0 for {@code java/lang/Object}/unloaded.
     */
    static long superclassOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        long st = Magic.load64(Magic.load64(mirror + 16L) + 8L);
        return st == 0L ? 0L : Loader.classMirror(st);
    }

    /**
     * M4: {@code Thread.currentThread0()} native — the calling task's guest Thread. Tasks started via
     * {@code Thread.start()} recorded their Thread in {@link #startThread}; a task the VM created without
     * one (the boot task) gets a bare Thread lazily wrapped around it (cached, so the answer is stable).
     */
    static long currentThreadObj()
    {
        int me = curTask();
        long t = taskThreadObj[me];
        if (t == 0L)
        {
            t = Loader.allocThreadObj();                   // 0 if java/lang/Thread isn't in the loaded batch
            taskThreadObj[me] = t;
        }
        return t;
    }

    static long fileOpen(long nameRef)
    {
        if (nameRef <= 0x1000L || fileDir == 0L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no RAMFS -> 0
        }
        long arr = strBytes(nameRef);                      // String -> its value byte[] (len@+16, data@+24)
        long len = Magic.load64(arr + 16L);
        int i = 0;
        while (i < (int) fileCount)
        {
            long e = fileDir + i * 32L;
            if (Magic.load64(e + 8L) == len)
            {
                long na = Magic.load64(e);                 // path bytes
                int k = 0;
                while (k < (int) len && Magic.load8(na + k) == Magic.load8(arr + 24L + k))
                {
                    k += 1;
                }
                if (k == (int) len)
                {
                    return e;
                }
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Resolve a guest {@code byte[]} hostname to an IPv4 address, returned as a big-endian int (a.b.c.d ->
     * (a&lt;&lt;24)|(b&lt;&lt;16)|(c&lt;&lt;8)|d), 0 on failure. Backs the overlay {@code java.net.InetAddress.resolve0}
     * with the WiFi DNS resolver; the socket layer reads this int straight out of the InetAddress.
     */
    static int dnsResolve(long hostArrRef)
    {
        if (hostArrRef <= 0x1000L)                         // boot-time force-compile passes 0
        {
            return 0;
        }
        int hlen = (int) Magic.load64(hostArrRef + 16L);   // guest byte[] length
        byte[] host = heapBytes(hostArrRef + 24L, hlen);
        long ipOut = Heap.allocData(4);
        if (!board.cyw43.Cyw43.dnsResolve(host, ipOut))
        {
            return 0;
        }
        return ((Magic.load8(ipOut) & 0xFF) << 24) | ((Magic.load8(ipOut + 1) & 0xFF) << 16)
                | ((Magic.load8(ipOut + 2) & 0xFF) << 8) | (Magic.load8(ipOut + 3) & 0xFF);
    }

    /** The net.Tcp handle stored in a FileDescriptor's fd field (offset 16 = first instance field). */
    private static int fdIndex(long fdRef)
    {
        return (int) Magic.load64(fdRef + 16L);
    }

    /** VarHandle overlay: byte offset of instance field named by the guest {@code byte[]} within {@code obj}'s
     *  class -> {@code java/lang/invoke/VarHandle.fieldOffset0(byte[],Object)J}. */
    static long vhFieldOffset(long fnameArrRef, long objRef)
    {
        if (fnameArrRef <= 0x1000L || objRef <= 0x1000L)     // boot-time force-compile passes 0
        {
            return -1L;
        }
        int fnLen = (int) Magic.load64(fnameArrRef + 16L);   // guest byte[] length
        long fnBase = fnameArrRef + 24L;                     // guest byte[] data
        long tib = Magic.load64(objRef);                     // obj header TIB
        return Loader.vhFieldOffset(fnBase, fnLen, tib);
    }

    /** Reflection: {@code Class.fieldMods0(Class,byte[])} -> the named own instance field's access_flags, or -1. */
    static int fieldMods(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;
        }
        long typeAddr = Magic.load64(mirrorRef + 16L);       // Class.typeAddr
        int fnLen = (int) Magic.load64(nameArrRef + 16L);
        long fnBase = nameArrRef + 24L;
        return Loader.fieldMods(typeAddr, fnBase, fnLen);
    }

    /** Reflection: {@code Class.fieldTypeChar0(Class,byte[])} -> the field's descriptor first char, or -1. */
    static int fieldTypeChar(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;
        }
        long typeAddr = Magic.load64(mirrorRef + 16L);
        int fnLen = (int) Magic.load64(nameArrRef + 16L);
        long fnBase = nameArrRef + 24L;
        return Loader.fieldTypeChar(typeAddr, fnBase, fnLen);
    }

    /** getCallerClass: the Class mirror of the JIT'd method containing machine PC {@code pc} (a saved LR). */
    static long classAtPc(long pc)
    {
        if (pc <= 0x1000L)
        {
            return 0L;
        }
        return Loader.classMirrorAtPc(pc);
    }

    /** Net.socket0(preferIPv6, stream, reuse, fastLoopback) -> a fresh net.Tcp fd (the flags are ignored). */
    static int sockSocket0(long a, long b, long c, long d)
    {
        return net.Tcp.alloc();
    }

    /** Net.connect0(preferIPv6, FileDescriptor fd, InetAddress remote, int port) -> 1 on success, 0 else. */
    static int sockConnect0(long preferIPv6, long fdRef, long inetRef, long port)
    {
        int ipBe = (int) Magic.load64(inetRef + 16L);   // InetAddress.addr (first field, big-endian IPv4)
        return net.Tcp.connect(fdIndex(fdRef), ipBe, (int) port);
    }

    /** SocketDispatcher.read0(fd, address, len) -> bytes read into {@code address}, or -1 at EOF. */
    static int sockRead0(long fdRef, long address, long len)
    {
        return net.Tcp.read(fdIndex(fdRef), address, 0, (int) len);
    }

    /** SocketDispatcher.write0(fd, address, len) -> bytes written from {@code address}. */
    static int sockWrite0(long fdRef, long address, long len)
    {
        return net.Tcp.write(fdIndex(fdRef), address, 0, (int) len);
    }

    /** UnixDispatcher.close0(fd). */
    static void sockClose0(long fdRef)
    {
        net.Tcp.close(fdIndex(fdRef));
    }

    /** Net.available(fd) -> bytes buffered for a non-blocking read. */
    static int sockAvailable(long fdRef)
    {
        return net.Tcp.available(fdIndex(fdRef));
    }

    /** IOUtil.fdVal(fd) -> the fd int (the net.Tcp handle). */
    static int fdVal(long fdRef)
    {
        return (int) Magic.load64(fdRef + 16L);
    }

    /** IOUtil.setfdVal(fd, value). */
    static void setFdVal(long fdRef, long value)
    {
        Magic.store64(fdRef + 16L, value);
    }

    /** Shared no-op for the void socket natives (UnixDispatcher.init/preClose0, IOUtil.initIDs,
     *  NativeThread.init) -- never reached on the blocking happy path. */
    static void sockNoop()
    {
    }

    /** Shared 0 for the socket natives we stub: Net.localPort / getIntOption0 (SO_LINGER=0 -> close skips
     *  shutdown) / localInetAddress (null wildcard), NativeThread.current0. */
    static long sockZero()
    {
        return 0L;
    }

    static void printStackTrace(long self)
    {
        if (self <= 0x1000L)
        {
            return;                                        // the boot-time force-compile calls this with 0; no-op
        }
        Uart.putc(0x0A);
        long tib = Magic.load64(self);
        if (tib > 0x1000L)
        {
            Loader.printClassName(Magic.load64(tib));      // TIB[0] = Type -> the exception's class name
        }
        long msg = Magic.load64(self + 80L);               // Throwable.detailMessage (after the 8-slot backtrace)
        if (msg > 0x1000L)
        {
            Uart.write(Magic.bytes(": "));
            printStr(msg);
        }
        Uart.putc(0x0A);
        int i = 0;
        while (i < 8)
        {
            long fpc = Magic.load64(self + 16L + i * 8L);
            if (fpc == 0L)
            {
                break;
            }
            Uart.write(Magic.bytes("  at "));
            Loader.printFrameAt(fpc);
            Uart.putc(0x0A);
            i += 1;
        }
    }
}
