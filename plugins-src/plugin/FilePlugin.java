package plugin;

/**
 * An external "plugin" class for joe-ng reflection arc M4. It is compiled into {@code ramfs/plugins/} (NOT the
 * loader's classDir), so on the metal it exists ONLY as a file the guest reads and hands to
 * {@code ClassLoader.defineClass} — never reachable by {@code forName}. Dependency-free (just {@code Object} +
 * int math) so plain {@code javac} against the seed JDK produces a classfile the loader parses directly.
 */
public class FilePlugin
{
    private int seed;

    public FilePlugin()
    {
        seed = 100;
    }

    public int scale(int x)
    {
        return seed + x * 3;                             // 100 + 7*3 = 121 -- proves ctor field + method both ran
    }
}
