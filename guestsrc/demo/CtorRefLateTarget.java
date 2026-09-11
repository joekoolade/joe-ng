package demo;

import java.util.List;

/** Named only by a constructor reference inside a reflectively-reached method, so RTA never pulls it. */
public final class CtorRefLateTarget
{
    private final List<String> items;

    public CtorRefLateTarget(List<String> items)
    {
        this.items = items;
    }

    public String tag()
    {
        return "late-ctor-ref:" + items.size();
    }
}
