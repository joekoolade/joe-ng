package writer;

import classfile.ClassFile;
import util.StrSet;
import util.Vec;

/**
 * The seed-JVM {@link ClassModel}: every query resolved against the JDK
 * {@link ClassFile} through a {@link ClassFile.Resolver} (the same one
 * {@link ImageBuilder} already is). This is the ClassFile-bound half that the metal
 * writer will not use — on metal a {@code Loader}-registry impl answers the same queries
 * from byte-offset registries (PLAN.md §M5.5c).
 */
final class SeedClassModel implements ClassModel
{
    private final ClassFile.Resolver resolver;

    SeedClassModel(ClassFile.Resolver resolver)
    {
        this.resolver = resolver;
    }

    public boolean isRoot(String cls)
    {
        return ClassFile.isRoot(cls);
    }
    public String superClassName(String cls)
    {
        return resolver.resolve(cls).superClassName();
    }
    public int instanceFieldCount(String cls)
    {
        return resolver.resolve(cls).instanceFieldCount();
    }
    public boolean hasClinit(String cls)
    {
        return resolver.resolve(cls).hasClinit();
    }
    public Vec<ClassFile.VSlot> vtable(String cls)
    {
        return ClassFile.vtable(cls, resolver);
    }
    public Vec<ClassFile.Method> interfaceMethods(String cls)
    {
        return resolver.resolve(cls).interfaceMethods();
    }
    public String findImpl(String cls, String name, String desc)
    {
        return ClassFile.findImpl(cls, name, desc, resolver);
    }
    public StrSet allInterfaces(String cls)
    {
        return ClassFile.allInterfaces(cls, resolver);
    }
}
