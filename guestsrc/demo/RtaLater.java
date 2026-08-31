package demo;

/** Implements {@link RtaLate}. Both are reached only from a reflectively-compiled body. */
public class RtaLater implements RtaLate
{
    public String late()
    {
        return "late-iface";
    }
}
