package vm;

/** One implementer of {@link Speaker}. */
public final class Robot implements Speaker
{
    @Override
    public int speak()
    {
        return 0x52;   // 'R'
    }
}
