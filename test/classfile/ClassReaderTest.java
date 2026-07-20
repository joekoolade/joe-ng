package classfile;

import harness.T;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates the shared, JDK-free {@link ClassReader} on the <em>seed JVM</em> — the
 * other half of its dual life. The same code is compiled into the image and used by
 * the on-metal loader, so agreement here means one parser really does serve both
 * contexts (M5).
 *
 * <p>Correctness is checked by cross-validating against {@link ClassFile}, the
 * existing JDK-based parser: both read the same {@code .class} and must agree on
 * the class name, superclass, interface-ness and member counts.
 *
 * <p>Run: {@code java classfile.ClassReaderTest <classesDir>}
 */
public final class ClassReaderTest
{
    public static void main(String[] args) throws Exception
    {
        Path dir = Path.of(args.length > 0 ? args[0] : "out");
        check(dir, "vm/Guest", false);
        check(dir, "vm/Beta", false);        // two methods; greet() is not at slot 0
        check(dir, "vm/Greeter", true);      // an interface: abstract methods, no code
        check(dir, "board/bcm2711/Uart", false);
        T.summary("class-reader");
    }

    /** Parse {@code name} with both readers and assert they agree. */
    private static void check(Path dir, String name, boolean iface) throws Exception
    {
        byte[] b = Files.readAllBytes(dir.resolve(name + ".class"));
        ClassFile cf = ClassFile.parse(dir.resolve(name + ".class"));

        int[] off = new int[ClassReader.cpCount(b)];
        int[] tag = new int[ClassReader.cpCount(b)];
        int afterCp = ClassReader.constantPool(b, off, tag);

        T.eqStr(name + " thisClass", cf.thisClassName(),
                utf8(b, ClassReader.thisClassNameOff(b, off, afterCp)));

        String sup = cf.superClassName();
        if (sup != null)
        {
            T.eqStr(name + " superClass", sup,
                    utf8(b, ClassReader.superClassNameOff(b, off, afterCp)));
        }

        T.check(name + " isInterface", ClassReader.isInterface(b, afterCp) == iface);

        // Walking to the methods table and reading its count must match the parser.
        int methods = ClassReader.methodsStart(b, afterCp);
        T.eq(name + " methodCount", cf.methods().length, ClassReader.u2(b, methods));

        // Every Class entry's name must decode to a real, non-empty string.
        int classes = 0;
        for (int i = 1; i < off.length; i++)
        {
            if (tag[i] == ClassReader.TAG_CLASS)
            {
                T.check(name + " class#" + i + " named",
                        !utf8(b, ClassReader.classNameOff(b, off, i)).isEmpty());
                classes++;
            }
        }
        T.check(name + " has Class entries", classes > 0);

        // utf8Eq is the cross-classfile comparison the loader links with.
        int self = ClassReader.thisClassNameOff(b, off, afterCp);
        T.check(name + " utf8Eq reflexive", ClassReader.utf8Eq(b, self, b, self));
    }

    /** Decode a Utf8 entry to a String — test-side only; the reader itself has no String. */
    private static String utf8(byte[] b, int off)
    {
        int len = ClassReader.u2(b, off);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
        {
            sb.append((char) ClassReader.u1(b, off + 2 + i));
        }
        return sb.toString();
    }
}
