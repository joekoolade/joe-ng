package java.lang.constant;

/**
 * Bare-metal stub of {@code java.lang.constant.Constable}. The constant/condy nominal-descriptor API is unused
 * on metal, but stock value classes (Integer/Long/Float/Double/String/...) declare {@code implements Constable,
 * ConstantDesc}. Structurally pulling the real interfaces drags the ENTIRE {@code java.lang.constant} package
 * (ClassDesc, MethodTypeDesc, DynamicConstantDesc, ConstantDescs, DirectMethodHandleDesc, ...) plus the
 * MethodHandle machinery into a demand-load closure. An empty marker interface (guest override wins via
 * addIfAbsent) stops that cascade at the root: {@code describeConstable()} is never reached on metal, so the
 * missing method is harmless.
 */
public interface Constable
{
}
