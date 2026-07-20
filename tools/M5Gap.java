// Reproduces the M5.1 gap measurement in PLAN.md: runs joe-ng's own compiler
// over a class and reports what it cannot yet compile, by category.
//   javac -cp out -d tools tools/M5Gap.java && java -cp out:tools M5Gap out compiler/BaselineCompiler
import classfile.ClassFile;
import compiler.BaselineCompiler;
import java.nio.file.*;
import java.util.*;

public class M5Gap {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Map<String,ClassFile> cache = new HashMap<>();
        BaselineCompiler.ClassResolver res = owner -> {
            try { return cache.computeIfAbsent(owner, o -> {
                try { return ClassFile.parse(out.resolve(o + ".class")); }
                catch (Exception e) { throw new RuntimeException(e); } }); }
            catch (Exception e) { throw new IllegalStateException("unresolvable " + owner); }
        };
        Map<String,Integer> opcodes = new TreeMap<>();
        Map<String,Integer> reasons = new TreeMap<>();
        Set<String> jdkRefs = new TreeSet<>();
        int ok = 0, failed = 0, overLocals = 0, total = 0;

        for (String cls : Arrays.copyOfRange(args, 1, args.length)) {
            ClassFile cf = ClassFile.parse(out.resolve(cls + ".class"));
            for (ClassFile.Method m : cf.methods()) {
                if (m.code == null) continue;
                total++;
                if (m.maxLocals > 10) overLocals++;
                try { new BaselineCompiler(cf, res).compileMethod(m, 0x80000L, false); ok++; }
                catch (UnsupportedOperationException e) {
                    failed++;
                    String s = e.getMessage();
                    opcodes.merge(s != null && s.startsWith("opcode") ? s.substring(0, s.indexOf(" at ")) : ""+s, 1, Integer::sum);
                } catch (Throwable t) {
                    failed++;
                    String s = String.valueOf(t.getMessage());
                    if (s.contains("unresolvable ")) {
                        String c = s.substring(s.indexOf("unresolvable ") + 13).trim();
                        if (c.startsWith("java/")) jdkRefs.add(c);
                        reasons.merge("references " + (c.startsWith("java/") ? "a JDK class" : "unbuilt " + c), 1, Integer::sum);
                    } else reasons.merge(t.getClass().getSimpleName() + ": " + s, 1, Integer::sum);
                }
            }
        }
        System.out.printf("methods: %d total, %d compile today, %d blocked%n", total, ok, failed);
        System.out.printf("methods over the 10-local ceiling: %d%n%n", overLocals);
        System.out.println("-- unsupported opcodes --");
        opcodes.forEach((k,v) -> System.out.printf("  %-22s %3d methods%n", k, v));
        System.out.println("\n-- other blockers --");
        reasons.forEach((k,v) -> System.out.printf("  %-48s %3d methods%n", k.length()>46?k.substring(0,46)+"..":k, v));
        System.out.println("\n-- JDK classes referenced --");
        jdkRefs.forEach(c -> System.out.println("  " + c));
    }
}
