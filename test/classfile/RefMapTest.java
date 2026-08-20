package classfile;

import java.nio.file.Files;
import java.nio.file.Path;
import harness.T;

/**
 * Pins {@link ClassFile#refMap} — the GC reference map the writer bakes into every Type node and the
 * on-metal loader rebuilds for metal-only classes. A wrong bit here is not a slow collection, it is a
 * live object freed, so the bits are asserted against classes whose field layout is written out below
 * rather than recomputed by the same logic under test.
 *
 * <p>Run: {@code java classfile.RefMapTest <classesDir>}
 */
public final class RefMapTest
{
    public static void main(String[] args) throws Exception
    {
        Path dir = Path.of(args.length > 0 ? args[0] : "out");
        ClassFile.Resolver r = new DirResolver(dir);

        // vm/RVMClass declares, in order: base(J) nameOff(I) tib(J) type(J) statics(J) fieldCount(I)
        // vtCount(I) vtStart(I) superReg(I) modifiers(I) isIface(Z) ifmStart(I) ifmCount(I) state(I).
        // Its super is Object (no instance fields), so slot i is field i. Bit 0 is the marker; a slot's
        // bit is 1+slot. Pointer-bearing = the four longs: slots 0, 2, 3, 4.
        long[] rvmClass = ClassFile.refMap("vm/RVMClass", r);
        T.eq("RVMClass map computed", 1L, rvmClass[0] & 1L);
        T.eq("RVMClass word0", (1L | 1L << 1 | 1L << 3 | 1L << 4 | 1L << 5), rvmClass[0]);
        T.eq("RVMClass word1", 0L, rvmClass[1]);
        T.eq("RVMClass int slot skipped", 0L, rvmClass[0] & 1L << 2);   // nameOff is an int: not scanned

        // vm/Cell holds a single int field: nothing for the collector to follow, but the map still says
        // "computed" — an all-zero payload map is the point, not a fallback.
        long[] cell = ClassFile.refMap("vm/Cell", r);
        T.eq("Cell map computed", 1L, cell[0] & 1L);
        T.eq("Cell has no pointer slots", 1L, cell[0]);

        // Descriptor classification, stated directly.
        T.eq("L is pointer-bearing", 1, ClassFile.mayHoldPointer("Ljava/lang/String;") ? 1 : 0);
        T.eq("[ is pointer-bearing", 1, ClassFile.mayHoldPointer("[B") ? 1 : 0);
        T.eq("J is pointer-bearing", 1, ClassFile.mayHoldPointer("J") ? 1 : 0);   // raw addresses live in longs
        T.eq("I is not", 0, ClassFile.mayHoldPointer("I") ? 1 : 0);
        T.eq("Z is not", 0, ClassFile.mayHoldPointer("Z") ? 1 : 0);
        T.eq("D is not", 0, ClassFile.mayHoldPointer("D") ? 1 : 0);

        // An unresolvable ancestor must yield NO map (its fields would shift every slot below it).
        long[] blind = ClassFile.refMap("vm/RVMClass", new BlindResolver(dir));
        T.eq("incomplete chain publishes no map", 0L, blind[0] | blind[1]);

        T.summary("refmap");
    }

    /** Resolves class names against compiled classfiles in a directory. */
    private record DirResolver(Path dir) implements ClassFile.Resolver
    {
        public ClassFile resolve(String owner)
        {
            try
            {
                return ClassFile.parse(dir.resolve(owner + ".class"));
            }
            catch (Exception e)
            {
                throw new RuntimeException(owner, e);
            }
        }

        public boolean canResolve(String owner)
        {
            return Files.exists(dir.resolve(owner + ".class"));
        }
    }

    /** Resolves the class itself but nothing above it — the resolver-less fixture case. */
    private record BlindResolver(Path dir) implements ClassFile.Resolver
    {
        public ClassFile resolve(String owner)
        {
            try
            {
                return ClassFile.parse(dir.resolve(owner + ".class"));
            }
            catch (Exception e)
            {
                throw new RuntimeException(owner, e);
            }
        }

        public boolean canResolve(String owner)
        {
            return owner.equals("vm/RVMClass");
        }
    }
}
