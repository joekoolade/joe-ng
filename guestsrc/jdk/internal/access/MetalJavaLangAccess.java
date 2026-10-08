/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-15
 */
package jdk.internal.access;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import jdk.internal.loader.NativeLibraries;
import jdk.internal.misc.CarrierThreadLocal;
import jdk.internal.module.ServicesCatalog;
import jdk.internal.reflect.ConstantPool;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import jdk.internal.vm.StackableScope;
import jdk.internal.vm.ThreadContainer;
import sun.reflect.annotation.AnnotationType;
import sun.nio.ch.Interruptible;

/**
 * Metal JavaLangAccess, seeded into {@code SharedSecrets.javaLangAccess} by the loader (stock
 * {@code System.<clinit>}, which registers the real one, does not run here).
 *
 * <p>Every member is one of three kinds, and rule 3 decides which. IMPLEMENTED exactly from public API where
 * stock's behaviour can be reproduced; a NO-OP only where doing nothing is the correct semantics on this VM
 * (each says why); otherwise it THROWS an {@code InternalError} naming itself. This class used to answer
 * null/0/false for ~60 members, and two of those plausible answers were measured as silent wrong answers VM-wide
 * ({@code join} made every {@code StringJoiner} answer null; {@code inflateBytesToChars} filled nothing). An
 * {@code Error} rather than a RuntimeException, for the reason {@code CaseFolding} records: a broad
 * {@code catch (Exception)} must not turn a missing capability into a silent wrong answer.
 */
public final class MetalJavaLangAccess implements JavaLangAccess
{
    private static InternalError unsupported(String member)
    {
        return new InternalError("joe-ng: JavaLangAccess." + member + " is not implemented on this VM");
    }

    @Override public List<Method> getDeclaredPublicMethods(Class<?> klass, String name, Class<?>... parameterTypes)
    {
        throw unsupported("getDeclaredPublicMethods");
    }
    @Override public Method findMethod(Class<?> klass, boolean publicOnly, String name, Class<?>... parameterTypes)
    {
        throw unsupported("findMethod");
    }
    @Override public ConstantPool getConstantPool(Class<?> klass)
    {
        throw unsupported("getConstantPool");
    }
    @Override public boolean casAnnotationType(Class<?> klass, AnnotationType oldType, AnnotationType newType)
    {
        throw unsupported("casAnnotationType");
    }
    @Override public AnnotationType getAnnotationType(Class<?> klass)
    {
        throw unsupported("getAnnotationType");
    }
    @Override public Map<Class<? extends Annotation>, Annotation> getDeclaredAnnotationMap(Class<?> klass)
    {
        throw unsupported("getDeclaredAnnotationMap");
    }
    @Override public byte[] getRawClassAnnotations(Class<?> klass)
    {
        throw unsupported("getRawClassAnnotations");
    }
    @Override public byte[] getRawClassTypeAnnotations(Class<?> klass)
    {
        throw unsupported("getRawClassTypeAnnotations");
    }
    @Override public byte[] getRawExecutableTypeAnnotations(Executable executable)
    {
        throw unsupported("getRawExecutableTypeAnnotations");
    }
    @Override public int getClassFileAccessFlags(Class<?> klass)
    {
        throw unsupported("getClassFileAccessFlags");
    }
    @Override public <E extends Enum<E>> E[] getEnumConstantsShared(Class<E> klass)
    {
        return (E[]) (Object) klass.getEnumConstants();   // metal Class.getEnumConstants (values() reflection)
    }
    @Override public int classFileVersion(Class<?> clazz)
    {
        throw unsupported("classFileVersion");
    }
    @Override public void blockedOn(Interruptible b)
    {
        throw unsupported("blockedOn");
    }
    @Override public void registerShutdownHook(int slot, boolean registerShutdownInProgress, Runnable hook)
    {
        // Correct as a no-op: a bare-metal VM never runs the JVM exit sequence, so a hook could never fire.
    }
    @Override public void invokeFinalize(Object o) throws Throwable
    {
        throw unsupported("invokeFinalize");
    }
    @Override public ConcurrentHashMap<?, ?> createOrGetClassLoaderValueMap(ClassLoader cl)
    {
        throw unsupported("createOrGetClassLoaderValueMap");
    }
    @Override public Class<?> defineClass(ClassLoader cl, String name, byte[] b, ProtectionDomain pd, String source)
    {
        throw unsupported("defineClass");
    }
    @Override public Class<?> defineClass(ClassLoader cl, Class<?> lookup, String name, byte[] b, ProtectionDomain pd, boolean initialize, int flags, Object classData)
    {
        throw unsupported("defineClass");
    }
    @Override public Class<?> findBootstrapClassOrNull(String name)
    {
        throw unsupported("findBootstrapClassOrNull");
    }
    @Override public Package definePackage(ClassLoader cl, String name, Module module)
    {
        throw unsupported("definePackage");
    }
    @Override public Module defineModule(ClassLoader loader, ModuleDescriptor descriptor, URI uri)
    {
        throw unsupported("defineModule");
    }
    @Override public Module defineUnnamedModule(ClassLoader loader)
    {
        throw unsupported("defineUnnamedModule");
    }
    @Override public void addReads(Module m1, Module m2)
    {
        // Correct as a no-op: joe-ng has ONE unnamed module, which reads, exports and opens everything already.
    }
    @Override public void addReadsAllUnnamed(Module m)
    {
        // Correct as a no-op: one unnamed module, which already reads everything.
    }
    @Override public void addExports(Module m1, String pkg)
    {
        // Correct as a no-op: one unnamed module, which already exports every package.
    }
    @Override public void addExports(Module m1, String pkg, Module m2)
    {
        // Correct as a no-op: one unnamed module, which already exports every package.
    }
    @Override public void addExportsToAllUnnamed(Module m, String pkg)
    {
        // Correct as a no-op: one unnamed module, which already exports every package.
    }
    @Override public void addOpens(Module m1, String pkg, Module m2)
    {
        // Correct as a no-op: one unnamed module, which already opens every package.
    }
    @Override public void addOpensToAllUnnamed(Module m, String pkg)
    {
        // Correct as a no-op: one unnamed module, which already opens every package.
    }
    @Override public void addUses(Module m, Class<?> service)
    {
        // Correct as a no-op: an unnamed module may use any service; uses is not checked here.
    }
    @Override public boolean isReflectivelyExported(Module module, String pn, Module other)
    {
        // One unnamed module, which exports and opens every package: true is the exact answer. Was false.
        return true;
    }
    @Override public boolean isReflectivelyOpened(Module module, String pn, Module other)
    {
        // One unnamed module, which exports and opens every package: true is the exact answer. Was false.
        return true;
    }
    @Override public void addEnableNativeAccess(Module m)
    {
        throw unsupported("addEnableNativeAccess");
    }
    @Override public boolean addEnableNativeAccess(ModuleLayer layer, String name)
    {
        throw unsupported("addEnableNativeAccess");
    }
    @Override public void addEnableNativeAccessToAllUnnamed()
    {
        throw unsupported("addEnableNativeAccessToAllUnnamed");
    }
    @Override public void ensureNativeAccess(Module m, Class<?> owner, String methodName, Class<?> currentClass, boolean jni)
    {
        throw unsupported("ensureNativeAccess");
    }
    @Override public void addEnableFinalMutationToAllUnnamed()
    {
        throw unsupported("addEnableFinalMutationToAllUnnamed");
    }
    @Override public boolean tryEnableFinalMutation(Module m)
    {
        throw unsupported("tryEnableFinalMutation");
    }
    @Override public boolean isFinalMutationEnabled(Module m)
    {
        throw unsupported("isFinalMutationEnabled");
    }
    @Override public boolean isStaticallyExported(Module module, String pn, Module other)
    {
        // One unnamed module, which exports and opens every package: true is the exact answer. Was false.
        return true;
    }
    @Override public boolean isStaticallyOpened(Module module, String pn, Module other)
    {
        // One unnamed module, which exports and opens every package: true is the exact answer. Was false.
        return true;
    }
    @Override public ServicesCatalog getServicesCatalog(ModuleLayer layer)
    {
        throw unsupported("getServicesCatalog");
    }
    @Override public void bindToLoader(ModuleLayer layer, ClassLoader loader)
    {
        throw unsupported("bindToLoader");
    }
    @Override public Stream<ModuleLayer> layers(ModuleLayer layer)
    {
        throw unsupported("layers");
    }
    @Override public Stream<ModuleLayer> layers(ClassLoader loader)
    {
        throw unsupported("layers");
    }
    @Override public int countPositives(byte[] ba, int off, int len)
    {
        // Stock StringCoding.countPositives: the number of leading NON-NEGATIVE bytes. Was 0.
        int i = 0;
        while (i < len)
        {
            if (ba[off + i] < 0)
            {
                return i;
            }
            i += 1;
        }
        return len;
    }
    @Override public int countNonZeroAscii(String s)
    {
        // Stock StringCoding.countNonZeroAscii: leading chars in U+0001..U+007F. The LATIN1 and UTF16 branches
        // stock takes agree on that definition, so charAt serves both. Was 0.
        int n = s.length();
        int i = 0;
        while (i < n)
        {
            char c = s.charAt(i);
            if (c == 0 || c > 0x7F)
            {
                return i;
            }
            i += 1;
        }
        return n;
    }
    /**
     * Wrap LATIN1 bytes as a String -- IMPLEMENTED, not stubbed, because a null here is a SILENT WRONG
     * ANSWER rather than a visible gap.
     *
     * <p>{@code BigDecimal.layoutChars} has a scale-2 "currency fast path" that lays the digits out itself
     * and hands the buffer to this method; answering null made {@code new BigDecimal("1.5").multiply(...)
     * .toString()} return null while every OTHER scale printed correctly -- so it looked like a formatting
     * quirk of four particular values rather than one missing member. Stock takes ownership of the array;
     * the ISO-8859-1 decode copies it, which is the same String and costs one array.
     */
    @Override public String uncheckedNewStringWithLatin1Bytes(byte[] bytes)
    {
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    @Override public String uncheckedNewStringOrThrow(byte[] bytes, Charset cs) throws CharacterCodingException
    {
        throw unsupported("uncheckedNewStringOrThrow");
    }
    @Override public byte[] uncheckedGetBytesOrThrow(String s, Charset cs) throws CharacterCodingException
    {
        throw unsupported("uncheckedGetBytesOrThrow");
    }
    @Override public char uncheckedGetUTF16Char(byte[] bytes, int index)
    {
        // StringUTF16.getChar. LITTLE-endian: StringUTF16.LO_BYTE_SHIFT is seeded to 8 on this VM, the same
        // order Loader.internString writes UTF16 literals in. Was 0.
        return (char) ((bytes[index << 1] & 0xFF) | ((bytes[(index << 1) + 1] & 0xFF) << 8));
    }
    @Override public void uncheckedPutCharUTF16(byte[] bytes, int index, int ch)
    {
        // StringUTF16.putChar, little-endian as above. Was an empty body.
        bytes[index << 1] = (byte) ch;
        bytes[(index << 1) + 1] = (byte) (ch >> 8);
    }
    @Override public byte[] getBytesUTF8OrThrow(String s) throws CharacterCodingException
    {
        throw unsupported("getBytesUTF8OrThrow");
    }
    /** Each LATIN1 byte widened to a char -- stock's {@code StringLatin1.inflate}. Was an empty body,
     *  which left {@code dst} untouched while the caller went on as if it had been filled. */
    @Override public void inflateBytesToChars(byte[] src, int srcOff, char[] dst, int dstOff, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[dstOff + i] = (char) (src[srcOff + i] & 0xFF);
            i += 1;
        }
    }
    @Override public int decodeASCII(byte[] src, int srcOff, char[] dst, int dstOff, int len)
    {
        // Stock String.decodeASCII: copy the leading non-negative bytes, widened, and answer how many. Was 0.
        int count = countPositives(src, srcOff, len);
        inflateBytesToChars(src, srcOff, dst, dstOff, count);
        return count;
    }
    @Override public InputStream initialSystemIn()
    {
        throw unsupported("initialSystemIn");
    }
    @Override public PrintStream initialSystemErr()
    {
        throw unsupported("initialSystemErr");
    }
    @Override public int uncheckedEncodeASCII(char[] src, int srcOff, byte[] dst, int dstOff, int len)
    {
        // Stock StringCoding.implEncodeAsciiArray: narrow chars below U+0080, stopping at the first that is
        // not, and answer how many. Was 0.
        int i = 0;
        while (i < len)
        {
            char c = src[srcOff + i];
            if (c >= 0x80)
            {
                return i;
            }
            dst[dstOff + i] = (byte) c;
            i += 1;
        }
        return len;
    }
    @Override public void setCause(Throwable t, Throwable cause)
    {
        throw unsupported("setCause");
    }
    @Override public ProtectionDomain protectionDomain(Class<?> c)
    {
        throw unsupported("protectionDomain");
    }
    @Override public MethodHandle stringConcatHelper(String name, MethodType methodType)
    {
        throw unsupported("stringConcatHelper");
    }
    @Override public Object uncheckedStringConcat1(String[] constants)
    {
        throw unsupported("uncheckedStringConcat1");
    }
    @Override public byte stringInitCoder()
    {
        // Stock: COMPACT_STRINGS ? LATIN1 : UTF16. Compact strings are on here (a String is LATIN1 whenever
        // every char fits a byte), so LATIN1 -- the previous 0 was already the right answer.
        return 0;
    }
    @Override public byte stringCoder(String str)
    {
        // String.coder() is package-private; with compact strings a String is LATIN1 (0) exactly when every
        // char fits a byte, UTF16 (1) otherwise. Was 0 for every string.
        int n = str.length();
        int i = 0;
        while (i < n)
        {
            if (str.charAt(i) > 0xFF)
            {
                return 1;
            }
            i += 1;
        }
        return 0;
    }
    /**
     * Stock's {@code String.join(prefix, suffix, delimiter, elements, size)} -- package-private to
     * {@code java.lang}, so built here from public calls. This stub answered NULL, so every
     * {@code StringJoiner.toString()} with elements (and every collector built on it) returned null across
     * the VM -- found by stock {@code Locale.toLanguageTag()} rendering a Unicode extension as {@code u-null}.
     */
    @Override public String join(String prefix, String suffix, String delimiter, String[] elements, int size)
    {
        StringBuilder sb = new StringBuilder(prefix);
        int i = 0;
        while (i < size)
        {
            if (i > 0)
            {
                sb.append(delimiter);
            }
            sb.append(elements[i]);
            i += 1;
        }
        sb.append(suffix);
        return sb.toString();
    }
    /** Stock's {@code StringConcatHelper.concat}: {@code prefix + String.valueOf(value) + suffix}. Was null. */
    @Override public String concat(String prefix, Object value, String suffix)
    {
        return prefix.concat(String.valueOf(value)).concat(suffix);
    }
    @Override public Object classData(Class<?> c)
    {
        throw unsupported("classData");
    }
    @Override public NativeLibraries nativeLibrariesFor(ClassLoader loader)
    {
        throw unsupported("nativeLibrariesFor");
    }
    @Override public Thread[] getAllThreads()
    {
        throw unsupported("getAllThreads");
    }
    @Override public ThreadContainer threadContainer(Thread thread)
    {
        throw unsupported("threadContainer");
    }
    @Override public void start(Thread thread, ThreadContainer container)
    {
        throw unsupported("start");
    }
    @Override public StackableScope headStackableScope(Thread thread)
    {
        throw unsupported("headStackableScope");
    }
    @Override public void setHeadStackableScope(StackableScope scope)
    {
        throw unsupported("setHeadStackableScope");
    }
    @Override public Thread currentCarrierThread()
    {
        // The carrier of a PLATFORM thread is the thread itself (Thread.currentCarrierThread's javadoc), and
        // joe-ng has no virtual threads, so this is exact. Reached by ThreadLocalRandom.getProbe, i.e. by
        // ConcurrentHashMap's contended counter.
        return Thread.currentThread();
    }
    @Override public <T> T getCarrierThreadLocal(CarrierThreadLocal<T> local)
    {
        throw unsupported("getCarrierThreadLocal");
    }
    @Override public <T> void setCarrierThreadLocal(CarrierThreadLocal<T> local, T value)
    {
        throw unsupported("setCarrierThreadLocal");
    }
    @Override public void removeCarrierThreadLocal(CarrierThreadLocal<?> local)
    {
        throw unsupported("removeCarrierThreadLocal");
    }
    @Override public Object[] scopedValueCache()
    {
        throw unsupported("scopedValueCache");
    }
    @Override public void setScopedValueCache(Object[] cache)
    {
        throw unsupported("setScopedValueCache");
    }
    @Override public Object scopedValueBindings()
    {
        throw unsupported("scopedValueBindings");
    }
    @Override public Continuation getContinuation(Thread thread)
    {
        throw unsupported("getContinuation");
    }
    @Override public void setContinuation(Thread thread, Continuation continuation)
    {
        throw unsupported("setContinuation");
    }
    @Override public ContinuationScope virtualThreadContinuationScope()
    {
        throw unsupported("virtualThreadContinuationScope");
    }
    @Override public void parkVirtualThread()
    {
        throw unsupported("parkVirtualThread");
    }
    @Override public void parkVirtualThread(long nanos)
    {
        throw unsupported("parkVirtualThread");
    }
    @Override public void unparkVirtualThread(Thread thread)
    {
        throw unsupported("unparkVirtualThread");
    }
    @Override public Executor virtualThreadDefaultScheduler()
    {
        throw unsupported("virtualThreadDefaultScheduler");
    }
    @Override public StackWalker newStackWalkerInstance(Set<StackWalker.Option> options, ContinuationScope contScope, Continuation continuation)
    {
        throw unsupported("newStackWalkerInstance");
    }
    @Override public String getLoaderNameID(ClassLoader loader)
    {
        throw unsupported("getLoaderNameID");
    }
    @Override public void copyToSegmentRaw(String string, MemorySegment segment, long offset)
    {
        throw unsupported("copyToSegmentRaw");
    }
    @Override public boolean bytesCompatible(String string, Charset charset)
    {
        throw unsupported("bytesCompatible");
    }
}
