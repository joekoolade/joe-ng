package demo;

/** The only class in the chain that implements {@link RtaLate}, and it declares no `viaDefault` of its own. */
public class RtaChainBase implements RtaLate
{
    public String late()
    {
        return "chain-late";
    }
}
