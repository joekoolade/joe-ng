package demo;

/**
 * An interface named ONLY from a reflectively-reached body, so RTA never pulls it and it is absent from the
 * batch entirely. That is the condition {@link RtaSpeak} does NOT reproduce: {@code RtaSpeak} is pulled (main
 * instantiates an implementor), and only the itable ENTRY was empty. Here the itable DIRECTORY has no entry
 * for this interface at all, and the interface Type baked into the call site is 0 as well.
 */
public interface RtaLate
{
    String late();

    /** A DEFAULT: no class declares it, so only the interface tier of the late resolve can find it. */
    default String viaDefault()
    {
        return "late-default";
    }
}
