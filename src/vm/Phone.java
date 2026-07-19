package vm;

/** A second implementer of {@link Speaker}, to show interface dispatch selects the right one. */
public final class Phone implements Speaker
{
    @Override
    public int speak()
    {
        return 0x50;   // 'P'
    }
}
