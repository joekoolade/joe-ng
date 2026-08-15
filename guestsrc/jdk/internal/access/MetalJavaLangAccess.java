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
 * Metal JavaLangAccess: only getEnumConstantsShared is real (routes to the working Class.getEnumConstants);
 * every other method is an unreached stub (RTA compiles only what the running code calls). Seeded into
 * SharedSecrets.javaLangAccess by the loader so EnumMap.getKeyUniverse works on metal (System.<clinit>,
 * which normally registers the JLA, is skipped). Generated from the JavaLangAccess interface.
 */
public final class MetalJavaLangAccess implements JavaLangAccess
{
    @Override public List<Method> getDeclaredPublicMethods(Class<?> klass, String name, Class<?>... parameterTypes)
    {
        return null;
    }
    @Override public Method findMethod(Class<?> klass, boolean publicOnly, String name, Class<?>... parameterTypes)
    {
        return null;
    }
    @Override public ConstantPool getConstantPool(Class<?> klass)
    {
        return null;
    }
    @Override public boolean casAnnotationType(Class<?> klass, AnnotationType oldType, AnnotationType newType)
    {
        return false;
    }
    @Override public AnnotationType getAnnotationType(Class<?> klass)
    {
        return null;
    }
    @Override public Map<Class<? extends Annotation>, Annotation> getDeclaredAnnotationMap(Class<?> klass)
    {
        return null;
    }
    @Override public byte[] getRawClassAnnotations(Class<?> klass)
    {
        return null;
    }
    @Override public byte[] getRawClassTypeAnnotations(Class<?> klass)
    {
        return null;
    }
    @Override public byte[] getRawExecutableTypeAnnotations(Executable executable)
    {
        return null;
    }
    @Override public int getClassFileAccessFlags(Class<?> klass)
    {
        return 0;
    }
    @Override public <E extends Enum<E>> E[] getEnumConstantsShared(Class<E> klass)
    {
        return (E[]) (Object) klass.getEnumConstants();   // metal Class.getEnumConstants (values() reflection)
    }
    @Override public int classFileVersion(Class<?> clazz)
    {
        return 0;
    }
    @Override public void blockedOn(Interruptible b)
    {
    }
    @Override public void registerShutdownHook(int slot, boolean registerShutdownInProgress, Runnable hook)
    {
    }
    @Override public void invokeFinalize(Object o) throws Throwable
    {
    }
    @Override public ConcurrentHashMap<?, ?> createOrGetClassLoaderValueMap(ClassLoader cl)
    {
        return null;
    }
    @Override public Class<?> defineClass(ClassLoader cl, String name, byte[] b, ProtectionDomain pd, String source)
    {
        return null;
    }
    @Override public Class<?> defineClass(ClassLoader cl, Class<?> lookup, String name, byte[] b, ProtectionDomain pd, boolean initialize, int flags, Object classData)
    {
        return null;
    }
    @Override public Class<?> findBootstrapClassOrNull(String name)
    {
        return null;
    }
    @Override public Package definePackage(ClassLoader cl, String name, Module module)
    {
        return null;
    }
    @Override public Module defineModule(ClassLoader loader, ModuleDescriptor descriptor, URI uri)
    {
        return null;
    }
    @Override public Module defineUnnamedModule(ClassLoader loader)
    {
        return null;
    }
    @Override public void addReads(Module m1, Module m2)
    {
    }
    @Override public void addReadsAllUnnamed(Module m)
    {
    }
    @Override public void addExports(Module m1, String pkg)
    {
    }
    @Override public void addExports(Module m1, String pkg, Module m2)
    {
    }
    @Override public void addExportsToAllUnnamed(Module m, String pkg)
    {
    }
    @Override public void addOpens(Module m1, String pkg, Module m2)
    {
    }
    @Override public void addOpensToAllUnnamed(Module m, String pkg)
    {
    }
    @Override public void addUses(Module m, Class<?> service)
    {
    }
    @Override public boolean isReflectivelyExported(Module module, String pn, Module other)
    {
        return false;
    }
    @Override public boolean isReflectivelyOpened(Module module, String pn, Module other)
    {
        return false;
    }
    @Override public void addEnableNativeAccess(Module m)
    {
    }
    @Override public boolean addEnableNativeAccess(ModuleLayer layer, String name)
    {
        return false;
    }
    @Override public void addEnableNativeAccessToAllUnnamed()
    {
    }
    @Override public void ensureNativeAccess(Module m, Class<?> owner, String methodName, Class<?> currentClass, boolean jni)
    {
    }
    @Override public void addEnableFinalMutationToAllUnnamed()
    {
    }
    @Override public boolean tryEnableFinalMutation(Module m)
    {
        return false;
    }
    @Override public boolean isFinalMutationEnabled(Module m)
    {
        return false;
    }
    @Override public boolean isStaticallyExported(Module module, String pn, Module other)
    {
        return false;
    }
    @Override public boolean isStaticallyOpened(Module module, String pn, Module other)
    {
        return false;
    }
    @Override public ServicesCatalog getServicesCatalog(ModuleLayer layer)
    {
        return null;
    }
    @Override public void bindToLoader(ModuleLayer layer, ClassLoader loader)
    {
    }
    @Override public Stream<ModuleLayer> layers(ModuleLayer layer)
    {
        return null;
    }
    @Override public Stream<ModuleLayer> layers(ClassLoader loader)
    {
        return null;
    }
    @Override public int countPositives(byte[] ba, int off, int len)
    {
        return 0;
    }
    @Override public int countNonZeroAscii(String s)
    {
        return 0;
    }
    @Override public String uncheckedNewStringWithLatin1Bytes(byte[] bytes)
    {
        return null;
    }
    @Override public String uncheckedNewStringOrThrow(byte[] bytes, Charset cs) throws CharacterCodingException
    {
        return null;
    }
    @Override public byte[] uncheckedGetBytesOrThrow(String s, Charset cs) throws CharacterCodingException
    {
        return null;
    }
    @Override public char uncheckedGetUTF16Char(byte[] bytes, int index)
    {
        return 0;
    }
    @Override public void uncheckedPutCharUTF16(byte[] bytes, int index, int ch)
    {
    }
    @Override public byte[] getBytesUTF8OrThrow(String s) throws CharacterCodingException
    {
        return null;
    }
    @Override public void inflateBytesToChars(byte[] src, int srcOff, char[] dst, int dstOff, int len)
    {
    }
    @Override public int decodeASCII(byte[] src, int srcOff, char[] dst, int dstOff, int len)
    {
        return 0;
    }
    @Override public InputStream initialSystemIn()
    {
        return null;
    }
    @Override public PrintStream initialSystemErr()
    {
        return null;
    }
    @Override public int uncheckedEncodeASCII(char[] src, int srcOff, byte[] dst, int dstOff, int len)
    {
        return 0;
    }
    @Override public void setCause(Throwable t, Throwable cause)
    {
    }
    @Override public ProtectionDomain protectionDomain(Class<?> c)
    {
        return null;
    }
    @Override public MethodHandle stringConcatHelper(String name, MethodType methodType)
    {
        return null;
    }
    @Override public Object uncheckedStringConcat1(String[] constants)
    {
        return null;
    }
    @Override public byte stringInitCoder()
    {
        return 0;
    }
    @Override public byte stringCoder(String str)
    {
        return 0;
    }
    @Override public String join(String prefix, String suffix, String delimiter, String[] elements, int size)
    {
        return null;
    }
    @Override public String concat(String prefix, Object value, String suffix)
    {
        return null;
    }
    @Override public Object classData(Class<?> c)
    {
        return null;
    }
    @Override public NativeLibraries nativeLibrariesFor(ClassLoader loader)
    {
        return null;
    }
    @Override public Thread[] getAllThreads()
    {
        return null;
    }
    @Override public ThreadContainer threadContainer(Thread thread)
    {
        return null;
    }
    @Override public void start(Thread thread, ThreadContainer container)
    {
    }
    @Override public StackableScope headStackableScope(Thread thread)
    {
        return null;
    }
    @Override public void setHeadStackableScope(StackableScope scope)
    {
    }
    @Override public Thread currentCarrierThread()
    {
        return null;
    }
    @Override public <T> T getCarrierThreadLocal(CarrierThreadLocal<T> local)
    {
        return null;
    }
    @Override public <T> void setCarrierThreadLocal(CarrierThreadLocal<T> local, T value)
    {
    }
    @Override public void removeCarrierThreadLocal(CarrierThreadLocal<?> local)
    {
    }
    @Override public Object[] scopedValueCache()
    {
        return null;
    }
    @Override public void setScopedValueCache(Object[] cache)
    {
    }
    @Override public Object scopedValueBindings()
    {
        return null;
    }
    @Override public Continuation getContinuation(Thread thread)
    {
        return null;
    }
    @Override public void setContinuation(Thread thread, Continuation continuation)
    {
    }
    @Override public ContinuationScope virtualThreadContinuationScope()
    {
        return null;
    }
    @Override public void parkVirtualThread()
    {
    }
    @Override public void parkVirtualThread(long nanos)
    {
    }
    @Override public void unparkVirtualThread(Thread thread)
    {
    }
    @Override public Executor virtualThreadDefaultScheduler()
    {
        return null;
    }
    @Override public StackWalker newStackWalkerInstance(Set<StackWalker.Option> options, ContinuationScope contScope, Continuation continuation)
    {
        return null;
    }
    @Override public String getLoaderNameID(ClassLoader loader)
    {
        return null;
    }
    @Override public void copyToSegmentRaw(String string, MemorySegment segment, long offset)
    {
    }
    @Override public boolean bytesCompatible(String string, Charset charset)
    {
        return false;
    }
}
