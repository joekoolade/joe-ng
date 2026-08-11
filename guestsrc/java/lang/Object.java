package java.lang;

import magic.Magic;

/**
 * A JDK-free, minimal {@code java/lang/Object}: the root class. It exists so {@code hashCode()},
 * {@code equals(Object)} AND {@code toString()} get canonical vtable slots that subclasses ({@link String})
 * override — which is what lets a polymorphic (static-type {@code Object}) call dispatch into the receiver's
 * real implementation: {@code java/util/HashMap} does {@code key.hashCode()}/{@code key.equals()}, and
 * {@code String.valueOf(Object)}/{@code String.join} do {@code element.toString()}. Object MUST declare each
 * of these, else the method's slot is never canonicalised (the name+desc fallback in
 * {@code Loader.globalVtableSlot} picks some other class's slot) and the override lands at a mismatched slot —
 * a {@code blr 0} wild branch. These root bodies are placeholders (String overrides all three); a real
 * identity hash / class-name toString would need a native. Compiled as a {@code java.base} patch.
 */
public class Object
{
    /** Intrinsified at EVERY call site ({@code getClass()Ljava/lang/Class;} -> {@code VM.getClassOf}, the
     *  header->TIB->Type->mirror walk); this body is never dispatched. Declared so guest sources compile
     *  against the overlay Object (M4 ReflectDemo). */
    public final Class getClass()
    {
        return null;
    }

    public int hashCode()
    {
        return 0;
    }

    public boolean equals(Object o)
    {
        return this == o;
    }

    public String toString()
    {
        return "object";
    }

    // ----- Object monitors. Appended AFTER hashCode/equals/toString so their canonical vtable slots don't
    // shift. wait/notify are final (no subclass overrides), so every object's slot points here; each lowers
    // to a Magic intrinsic -> a VM scheduler helper. The monitor lock itself is a no-op on this scheduler.

    public final void wait() throws InterruptedException
    {
        Magic.mwait(this, 0L);
    }

    public final void wait(long ms) throws InterruptedException
    {
        Magic.mwait(this, ms);
    }

    public final void notify()
    {
        Magic.mnotify(this);
    }

    public final void notifyAll()
    {
        Magic.mnotall(this);
    }
}
