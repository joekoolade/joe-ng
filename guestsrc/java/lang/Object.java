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

    /** Identity hash: a reference IS the object's heap address (non-moving GC), so the low 32 bits are a stable
     *  per-object hash -- the same value {@code System.identityHashCode} returns. Subclasses that care override. */
    public int hashCode()
    {
        return (int) Magic.addrOf(this);
    }

    public boolean equals(Object o)
    {
        return this == o;
    }

    /** {@code getClass().getName() + "@" + Integer.toHexString(hashCode())}, matching stock Object.toString (and
     *  Objects.toIdentityString). getClass() is intrinsified; the concat lowers to the metal's makeConcatWithConstants. */
    public String toString()
    {
        return getClass().getName() + "@" + Integer.toHexString(hashCode());
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

    /** Shallow copy: a fresh same-size block with this object's TIB + fields copied (the VM reads the block
     *  size from the status word). Appended after the finals so the canonical hashCode/equals/toString slots
     *  don't shift. Cloneable collections (LinkedList/ArrayList/HashMap) call {@code super.clone()} to get this
     *  shallow copy, then fix up their own links. Array clone uses the {@code [T.clone()} intrinsic instead. */
    protected Object clone() throws CloneNotSupportedException
    {
        return clone0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.objectClone}). */
    private static native Object clone0(Object o);
}
