package writer;

import classfile.ClassFile;
import compiler.BaselineCompiler;

import java.nio.file.Path;

/**
 * HOST diagnostic (#43): compile one method with VLOG=1 to dump every invokevirtual's (word offset, vtable slot,
 * owner.name) -- to find which call at a faulting metal offset dispatches through a bad slot.
 * Usage: {@code VLOG=1 java -cp out writer.VCallProbe <owner> <name> <desc> [classesDir]}
 */
public final class VCallProbe
{
    private VCallProbe() {}

    public static void main(String[] args) throws Exception
    {
        String owner = args[0];
        String name = args[1];
        String desc = args[2];
        Path dir = Path.of(args.length > 3 ? args[3] : "out");
        ClassRegistry reg = BuildRuntimeImage.populateRegistry(dir);
        ClassFile cf = reg.resolve(owner);
        BaselineCompiler.ClassResolver res = reg::resolve;
        var cm = new BaselineCompiler(cf, res).compileMethod(cf.method(name, desc), 0x02090460L, false);
        System.err.println("compiled " + owner + "." + name + desc + " -> " + cm.words().length + " words");
    }
}
