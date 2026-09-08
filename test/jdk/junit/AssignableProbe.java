import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code Class.isAssignableFrom} across INTERFACES -- the launcher's discovery blocker, reduced.
 *
 * <p>picocli decides whether an option is multi-valued with
 * {@code Collection.class.isAssignableFrom(field.getType())}. joe-ng's mirror walked only
 * {@code Type.superType}, the SUPERCLASS chain, so every interface answer was false: picocli treated
 * {@code List<ClassSelector> selectedClasses} as single-valued and set a bare {@code ClassSelector} into it,
 * and {@code getExplicitSelectors}' {@code list.addAll(getSelectedClasses())} then called
 * {@code toArray()} on a ClassSelector.
 *
 * <p>The class-chain arms are the control: those already passed, which is why the demo suite's
 * {@code Number.isAssignableFrom(Integer)} never caught this.
 */
public class AssignableProbe
{
    public static void main(String[] args)
    {
        // THE BUG: interface from interface, and interface from implementing class.
        System.out.println("Collection <- List      = " + Collection.class.isAssignableFrom(List.class) + " (want true)");
        System.out.println("Collection <- ArrayList = " + Collection.class.isAssignableFrom(ArrayList.class) + " (want true)");
        System.out.println("List       <- ArrayList = " + List.class.isAssignableFrom(ArrayList.class) + " (want true)");
        System.out.println("Iterable   <- ArrayList = " + Iterable.class.isAssignableFrom(ArrayList.class) + " (want true)");

        // CONTROL: the class chain, which already worked -- so a regression here is visible too.
        System.out.println("Number     <- Integer   = " + Number.class.isAssignableFrom(Integer.class) + " (want true)");
        System.out.println("Object     <- String    = " + Object.class.isAssignableFrom(String.class) + " (want true)");
        System.out.println("self       <- self      = " + List.class.isAssignableFrom(List.class) + " (want true)");

        // NEGATIVE: these must stay false, or the fix is just answering true.
        System.out.println("List       <- Collection= " + List.class.isAssignableFrom(Collection.class) + " (want false)");
        System.out.println("Integer    <- Number    = " + Integer.class.isAssignableFrom(Number.class) + " (want false)");
        System.out.println("List       <- String    = " + List.class.isAssignableFrom(String.class) + " (want false)");

        // What picocli actually asks, spelled out.
        boolean multi = Collection.class.isAssignableFrom(List.class);
        System.out.println("picocli isMultiValue(List) = " + multi + " (want true)");
        System.out.println("AssignableProbe done");
    }
}
