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
    public Vec<ClassModel.VSlot> vtable(String cls)
    {
        Vec<ClassFile.VSlot> slots = ClassFile.vtable(cls, resolver);
        Vec<ClassModel.VSlot> out = new Vec<>();
        for (int i = 0; i < slots.size(); i++)
        {
            ClassFile.VSlot s = slots.get(i);
            out.add(new ClassModel.VSlot(s.owner(), s.name(), s.descriptor()));
        }
        return out;
    }
    public Vec<ClassModel.Method> interfaceMethods(String cls)
    {
        Vec<ClassFile.Method> ms = resolver.resolve(cls).interfaceMethods();
        Vec<ClassModel.Method> out = new Vec<>();
        for (int i = 0; i < ms.size(); i++)
        {
            ClassFile.Method m = ms.get(i);
            out.add(new ClassModel.Method(m.name, m.descriptor));
        }
        return out;
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
