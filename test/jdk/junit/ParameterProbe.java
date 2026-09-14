import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * {@code Executable.getParameters()} through an EXECUTABLE-TYPED receiver -- the launcher's blocker, reduced.
 *
 * <p>JUnit's {@code ExtensionUtils.registerExtensionsFromExecutableParameters} takes an
 * {@code Executable} and calls {@code getParameters()} on it, so javac emits
 * {@code invokevirtual java/lang/reflect/Executable.getParameters}. joe-ng had no such class -- its
 * {@code Constructor} and {@code Method} both extended {@code AccessibleObject} directly -- so the call fell
 * to the late-virtual tier, which resolves against the RECEIVER and reported
 * {@code VIRTUALRESOLVE FAILED java/lang/reflect/Constructor.getParameters()}.
 *
 * <p>THE ARMS THAT DO THE REAL WORK ARE THE EXECUTABLE-TYPED ONES. Calling
 * {@code ctor.getParameters()} on a {@code Constructor}-typed reference compiles to an invokevirtual on
 * CONSTRUCTOR and would pass even with the class hierarchy unchanged -- reproducing the shape without the
 * condition, the trap recorded repeatedly in this project. Every arm below goes through a variable declared
 * as {@code Executable}, which is the launcher's own shape.
 *
 * <p>{@code getType()} is pinned by NAME rather than by non-nullness: eleven JUnit classes call it to decide
 * whether a resolver can supply a parameter, and a Parameter that answered the WRONG type would be non-null
 * and silently wrong. The two-parameter arms are what separate a walk that reads index 0 for everything from
 * one that indexes properly -- a single-parameter method cannot tell those apart.
 */
public class ParameterProbe
{
    /** Two parameters of DIFFERENT types, so an index bug cannot pass by both answering the same thing. */
    public static String target(int count, String label)
    {
        return label + count;
    }

    public static void noArgs()
    {
    }

    static class Held
    {
        Held(String name, long size)
        {
        }
    }

    public static void main(String[] args) throws Exception
    {
        // The parameter types are passed even though joe-ng's getDeclaredMethod resolves by NAME and ignores
        // them: the HOST control does not, and a probe that cannot run on the host proves nothing about what
        // the answers should be.
        Method m = ParameterProbe.class.getDeclaredMethod("target", int.class, String.class);
        Executable ex = m;                                 // the launcher's shape: an Executable-typed receiver

        System.out.println("method paramCount = " + ex.getParameterCount() + " (want 2)");
        Parameter[] ps = ex.getParameters();
        System.out.println("method params len = " + ps.length + " (want 2)");
        System.out.println("p0 name = " + ps[0].getName() + " (want arg0)");
        System.out.println("p1 name = " + ps[1].getName() + " (want arg1)");
        System.out.println("p0 type = " + nameOf(ps[0].getType()) + " (want int)");
        System.out.println("p1 type = " + nameOf(ps[1].getType()) + " (want java.lang.String)");
        System.out.println("p1 generic == type = " + (ps[1].getParameterizedType() == ps[1].getType()) + " (want true)");
        System.out.println("namePresent = " + ps[0].isNamePresent() + " (want false)");

        // getDeclaringExecutable is what AnnotationUtils.getEffectiveAnnotatedParameter calls FIRST, then
        // tests `instanceof Constructor` on the result -- so identity here is load-bearing, not decorative.
        System.out.println("p0 declaring == method = " + (ps[0].getDeclaringExecutable() == ex) + " (want true)");
        System.out.println("declaring instanceof Constructor = " + (ps[0].getDeclaringExecutable() instanceof Constructor)
                + " (want false)");

        // A FRESH array each call, as stock: JUnit streams over it and callers may mutate it.
        System.out.println("fresh array = " + (ex.getParameters() != ps) + " (want true)");

        // One row per parameter, never null and never short -- getEffectiveAnnotatedParameter indexes it
        // unguarded, so a short array is an AIOOBE inside JUnit rather than a missing annotation.
        System.out.println("annoRows = " + ex.getParameterAnnotations().length + " (want 2)");
        System.out.println("annoRow0 len = " + ex.getParameterAnnotations()[0].length + " (want 0)");

        // THE CONSTRUCTOR HALF, which is what actually failed: a Constructor could not answer its own
        // parameter types at all before this -- `resolve` discarded the registry index it had just looked up.
        Constructor<Held> c = Held.class.getDeclaredConstructor(String.class, long.class);
        Executable cex = c;
        System.out.println("ctor paramCount = " + cex.getParameterCount() + " (want 2)");
        Parameter[] cps = cex.getParameters();
        System.out.println("ctor params len = " + cps.length + " (want 2)");
        System.out.println("ctor p0 type = " + nameOf(cps[0].getType()) + " (want java.lang.String)");
        System.out.println("ctor p1 type = " + nameOf(cps[1].getType()) + " (want long)");
        System.out.println("ctor declaring instanceof Constructor = "
                + (cps[0].getDeclaringExecutable() instanceof Constructor) + " (want true)");
        System.out.println("ctor getName = " + cex.getName() + " (declaring class name)");

        // ZERO parameters must give an EMPTY array, never null: JUnit streams it unguarded.
        Executable zex = ParameterProbe.class.getDeclaredMethod("noArgs");
        Parameter[] zps = zex.getParameters();
        System.out.println("noArgs params len = " + zps.length + " (want 0)");
        System.out.println("noArgs annoRows = " + zex.getParameterAnnotations().length + " (want 0)");

        // Executable-typed reads of the members Member/AnnotatedElement would carry, which JUnit also calls.
        System.out.println("ex declaringClass = " + nameOf(ex.getDeclaringClass()) + " (want ParameterProbe)");
        System.out.println("ex modifiers nonZero = " + (ex.getModifiers() != 0) + " (want true)");
        System.out.println("ex toGenericString nonEmpty = " + (ex.toGenericString().length() > 0) + " (want true)");
        System.out.println("ctor toGenericString nonEmpty = " + (cex.toGenericString().length() > 0) + " (want true)");
        System.out.println("ex paramTypes len = " + ex.getParameterTypes().length + " (want 2)");
        System.out.println("ctor paramTypes len = " + cex.getParameterTypes().length + " (want 2)");

        // THE LAUNCHER'S EXACT SHAPE, and the one that failed on hardware:
        // ExtensionUtils.registerExtensionsFromExecutableParameters does Arrays.stream(getParameters()).
        // The Pi reported `ClassCastException: class <synthesised, implements nothing> cannot be cast to
        // class <synthesised, implements nothing>` with `stream`/`spliterator` frames -- and BOTH sides
        // rendering identically is what made it unreadable, since that string covered arrays as well as
        // lambdas. A stream over a REFERENCE array built by getParameters() is the condition, so it is
        // reproduced here rather than inferred.
        Parameter[] sp = ex.getParameters();
        long counted = java.util.Arrays.stream(sp).count();
        System.out.println("stream count = " + counted + " (want 2)");
        Object[] back = java.util.Arrays.stream(sp).toArray();
        System.out.println("stream toArray len = " + back.length + " (want 2)");

        // The CAST the pipeline makes on the way back: a typed toArray, which is a checkcast on an array
        // type. An array Type carries no registry entry and no itable directory, so a failure here used to
        // print exactly like an unresolved lambda interface.
        Parameter[] typed = java.util.Arrays.stream(sp).toArray(Parameter[]::new);
        System.out.println("typed toArray len = " + typed.length + " (want 2)");
        System.out.println("typed instanceof Parameter[] = " + (typed instanceof Parameter[]) + " (want true)");
        System.out.println("typed[0] type = " + nameOf(typed[0].getType()) + " (want int)");

        // A plain cast of the array itself, the simplest form of the same question.
        Object asObj = sp;
        Parameter[] recast = (Parameter[]) asObj;
        System.out.println("recast len = " + recast.length + " (want 2)");

        System.out.println("ParameterProbe done");
    }

    /** An unresolved type answers null rather than a plausible {@code Object}; say so rather than NPE. */
    private static String nameOf(Class<?> c)
    {
        return c == null ? "<null>" : c.getName();
    }
}
