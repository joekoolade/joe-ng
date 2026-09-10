package demo;

/**
 * The DEEP end of a chain whose interface is implemented TWO CLASSES UP.
 *
 * <p>{@link RtaChainBase} implements {@link RtaLate}; this class and {@link RtaChainMid} implement nothing.
 * So a late resolve that collects only the RECEIVER's own interfaces searches an EMPTY list -- which is
 * exactly JUnit's shape (SuiteEngineDescriptor extends EngineDescriptor extends AbstractTestDescriptor
 * implements TestDescriptor, with {@code accept} a default on TestDescriptor).
 *
 * <p>Reached only reflectively, like the rest of this family, so RTA never pulls {@link RtaLate} and the
 * resolve really is late; instantiated directly it resolves through the ordinary itable and tests nothing.
 */
public class RtaChainLeaf extends RtaChainMid
{
}
