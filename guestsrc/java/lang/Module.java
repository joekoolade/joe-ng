package java.lang;

/**
 * The bare minimum of a module for joe-ng: a single UNNAMED module that every class belongs to.
 *
 * <p>joe-ng has no module layer at all -- the boot image is a flat class directory plus demand-loaded jar
 * entries, with no module graph, no readability edges and no {@code ModuleDescriptor}. Stock library code
 * nonetheless reaches {@link Class#getModule()} on ordinary paths: JUnit's {@code ModuleUtils.getModuleVersion}
 * does {@code getModule().isNamed()} and returns {@code Optional.empty()} when it is false, so an unnamed
 * module answers it correctly rather than approximately -- an unnamed module genuinely HAS no descriptor and
 * no version, which is exactly what joe-ng's flat class space is.
 *
 * <p>{@link #getDescriptor()} therefore returns null, matching the JDK's own contract for an unnamed module
 * ("returns null if this module is an unnamed module"). A caller that reaches it without checking
 * {@link #isNamed()} first is making an unsound assumption and gets an NPE at its own site, which is a better
 * outcome than a fabricated descriptor.
 */
public final class Module
{
    /** Every class belongs to this one instance -- there is no second module to distinguish it from. */
    static final Module UNNAMED = new Module();

    private Module()
    {
    }

    /** Always false: joe-ng's class space is flat, so nothing is in a named module. */
    public boolean isNamed()
    {
        return false;
    }

    /** Null for an unnamed module, per the JDK contract. */
    public java.lang.module.ModuleDescriptor getDescriptor()
    {
        return null;
    }

    /** Null for an unnamed module, per the JDK contract. */
    public String getName()
    {
        return null;
    }

    public ClassLoader getClassLoader()
    {
        return null;
    }

    /** Null: joe-ng has no module GRAPH either, so there is no layer to report. */
    public ModuleLayer getLayer()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return "unnamed module";
    }
}
