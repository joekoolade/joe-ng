import java.util.List;
import java.util.stream.Stream;

/**
 * Stream pipeline evaluation on the metal -- the launcher's discovery blocker, reduced.
 *
 * <p>JUnit's {@code DiscoveryRequestCreator.includedClassNamePatterns} evaluates a stream and the run dies
 * with {@code DISPATCH ON UNREGISTERED TYPE (receiver's class not in the registry): begin(J)V} at
 * {@code Sink$ChainedReference.begin}, reached through
 * {@code evaluateToArrayNode -> wrapAndCopyInto -> copyInto -> Streams$ConcatSpliterator.forEachRemaining}.
 * {@code TRAPWIRE index=-1} says late resolution, not a denylisted class -- so some Sink receiver has a Type
 * that is not in the class registry.
 *
 * <p>Arms escalate, simplest first, so a trap names the construct that breaks rather than "streams".
 * Each prints before AND after, because a trap kills the run and the last line printed is the locator.
 */
public class StreamProbe
{
    public static void main(String[] args)
    {
        // SINGLE-element lists throughout: List12.spliterator() branches on size, and only size 1 takes the
        // Collections.singletonSpliterator path the launcher takes. A two-element list goes to
        // super.spliterator() instead, which fails for an unrelated reason (AbstractImmutableList inherits
        // spliterator() from the List DEFAULT, and that resolve is a separate gap) -- so a two-element probe
        // does not reproduce this condition at all. That mistake cost one boot.
        System.out.println("A stream().count() ...");
        System.out.println("A = " + List.of("a").stream().count() + " (want 1)");

        System.out.println("B stream().toArray() ...");
        Object[] b = List.of("a").stream().toArray();
        System.out.println("B = " + b.length + " (want 1)");

        System.out.println("C map().toArray() ...");
        Object[] c = List.of("a").stream().map(s -> s + "!").toArray();
        System.out.println("C = " + c.length + " " + c[0] + " (want 1 a!)");

        // D is the launcher's exact shape: Streams$ConcatSpliterator over two singleton streams, evaluated
        // to an array -- evaluateToArrayNode -> wrapAndCopyInto -> copyInto -> forEachRemaining -> Sink chain.
        System.out.println("D concat().toArray() ...");
        Object[] d = Stream.concat(List.of("a").stream(), List.of("b").stream()).toArray();
        System.out.println("D = " + d.length + " (want 2)");

        System.out.println("E concat().map().toArray(String[]::new) ...");
        String[] e = Stream.concat(List.of("a").stream(), List.of("b").stream())
                .map(s -> s + "!")
                .toArray(String[]::new);
        System.out.println("E = " + e.length + " " + e[0] + " (want 2 a!)");

        System.out.println("StreamProbe done");
    }
}
