package java.lang.constant;

/**
 * Bare-metal stub of {@code java.lang.constant.ConstantDesc} — see {@link Constable}. Empty marker so stock
 * value classes that declare {@code implements ConstantDesc} do not drag the whole {@code java.lang.constant}
 * package into a demand-load closure. Its nominal-descriptor methods are never reached on metal.
 */
public interface ConstantDesc
{
}
