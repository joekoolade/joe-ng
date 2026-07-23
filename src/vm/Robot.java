package vm;

/** One implementer of {@link Speaker}. */
public final class Robot implements Speaker
{
    /** {@code new Robot(); s.speak()} via a Speaker ref — the metal writer's invokeinterface target. */
    public static int probe()
    {
        Speaker s = new Robot();
        return s.speak();
    }

    @Override
    public int speak()
    {
        return 0x52;   // 'R'
    }
}
