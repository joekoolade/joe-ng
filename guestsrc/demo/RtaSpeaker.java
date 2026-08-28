package demo;

/** Implements both, but nothing statically reachable calls {@link #pruned()}. */
public class RtaSpeaker implements RtaSpeak
{
    public String reached()
    {
        return "reached";
    }

    public String pruned()
    {
        return "pruned";
    }
}
