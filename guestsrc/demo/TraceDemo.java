package demo;

/**
 * Stack-trace smoke test: an uncaught null dereference deep in a call chain (c &larr; b &larr; a &larr; main).
 * The VM prints a Java-style stack trace -- exception class, then each frame as
 * {@code owner/Class.method(SourceFile.java:line) [pc=... +offset]} -- resolved on the metal from the
 * demand-compiled methods' line tables (Baseline's bci&rarr;PC map zipped with the classfile LineNumberTable).
 */
public class TraceDemo
{
    public static void main(String[] args)
    {
        a();
    }

    static void a()
    {
        b();
    }

    static void b()
    {
        c();
    }

    static void c()
    {
        String s = null;
        int x = s.length();   // uncaught NullPointerException thrown here
    }
}
