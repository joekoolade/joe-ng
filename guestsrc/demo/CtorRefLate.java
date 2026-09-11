package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reached ONLY reflectively, so RTA never walks this body: the constructor reference below is therefore
 * compiled LATE, and nothing has pulled {@link CtorRefLateTarget}. That is the condition the ordinary
 * ctor-reference demo cannot create -- there the target is in the batch, so the thunk bakes a valid TIB.
 */
public final class CtorRefLate
{
    public static String make()
    {
        Function<List<String>, CtorRefLateTarget> f = CtorRefLateTarget::new;
        Object o = f.apply(new ArrayList<String>());
        // The cast is the operation that failed in the launcher: a null-TIB object satisfies nothing.
        CtorRefLateTarget t = (CtorRefLateTarget) o;
        return t.tag();
    }
}
