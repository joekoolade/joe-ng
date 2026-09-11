package demo;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * java.util.concurrent.ConcurrentLinkedQueue on the metal. CLQ drives its whole structure through a
 * VarHandle -- {@code ITEM.set} in {@code Node.<init>}, {@code NEXT.set}, {@code HEAD/TAIL.compareAndSet},
 * {@code weakCompareAndSet}, {@code setRelease} -- and the overlay carried only two of those ops, so the
 * console launcher stopped in {@code Node.<init>} at ConcurrentLinkedQueue.java:193 with a DENYLIST TRAP
 * whose callee was EMPTY (index=-1: a late-resolution failure, not a denial).
 *
 * <p>The arms exercise DIFFERENT ops, so a partially-working shim is still caught: construction reaches
 * {@code set}, {@code offer} reaches {@code compareAndSet}/{@code weakCompareAndSet} on TAIL, and
 * {@code poll} reaches {@code setRelease} and {@code get} as it unlinks.
 */
public final class ClqDemo
{
    public static void main(String[] args)
    {
        System.out.println("ConcurrentLinkedQueue (VarHandle-driven, demand-loaded):");

        ConcurrentLinkedQueue<String> q = new ConcurrentLinkedQueue<String>();
        System.out.println("  empty        = " + (q.isEmpty() ? 1 : 0) + " (want 1)");
        System.out.println("  peek empty   = " + q.peek() + " (want null)");

        // offer -> Node.<init> (ITEM.set) + TAIL compareAndSet/weakCompareAndSet
        q.offer("a");
        q.offer("b");
        q.offer("c");
        System.out.println("  size after 3 = " + q.size() + " (want 3)");
        System.out.println("  peek         = " + q.peek() + " (want a)");

        // poll -> ITEM.setRelease/compareAndSet and the unlink path
        System.out.println("  poll         = " + q.poll() + " (want a)");
        System.out.println("  poll         = " + q.poll() + " (want b)");
        System.out.println("  size after 2 = " + q.size() + " (want 1)");

        // iteration reads through the handles too
        int n = 0;
        for (String s : q)
        {
            n += 1;
        }
        System.out.println("  iterated     = " + n + " (want 1)");
        System.out.println("  contains c   = " + (q.contains("c") ? 1 : 0) + " (want 1)");
        System.out.println("  poll         = " + q.poll() + " (want c)");
        System.out.println("  empty again  = " + (q.isEmpty() ? 1 : 0) + " (want 1)");
        System.out.println("  poll empty   = " + q.poll() + " (want null)");

        // A second queue: the handles are STATIC, so a stale binding would show up here.
        ConcurrentLinkedQueue<String> q2 = new ConcurrentLinkedQueue<String>();
        q2.offer("x");
        System.out.println("  second queue = " + q2.poll() + " (want x)");

        System.out.println("ClqDemo done");
    }
}
