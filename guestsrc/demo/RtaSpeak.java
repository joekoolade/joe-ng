package demo;

/** Two methods: one statically reachable, one named only from a reflectively-reached body. */
public interface RtaSpeak
{
    String reached();

    String pruned();
}
