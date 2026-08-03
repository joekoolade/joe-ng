package demo;

import magic.Magic;

/**
 * M4: Thread + Class-reflection widening. Class side: {@code getName} (VM builds the dotted name from the
 * loader registry), {@code isInstance} (the {@code VM.instanceOf} walk), {@code isAssignableFrom} (pure-Java
 * Type-chain walk), {@code getSuperclass} (cached mirrors, so it {@code ==} the class literal), and mirror
 * identity. Thread side: {@code currentThread()} is stable on the boot task (a lazy VM wrapper) and returns
 * the EXACT started Thread inside a spawned task ({@code VM.startThread} records the spawned receiver);
 * {@code getName} carries the constructor name; {@code sleep} keeps the main task off the CPU while the
 * worker runs.
 */
public class ReflectDemo
{
    static int spawnedOk;                                // set by the worker task: currentThread() == self

    public static void main()
    {
        Class sc = "hi".getClass();
        Magic.printStr("String.getName=" + sc.getName() + "\n");                    // java.lang.String
        Class ic = Integer.class;
        Class nc = Number.class;
        Magic.printStr("Integer.getName=" + ic.getName() + "\n");                   // java.lang.Integer
        Magic.printStr("isInstance: str=" + (sc.isInstance("x") ? 1 : 0)
                + " num=" + (nc.isInstance("x") ? 1 : 0)
                + " null=" + (sc.isInstance(null) ? 1 : 0) + "\n");                 // 1 0 0
        Magic.printStr("Number.isAssignableFrom(Integer)=" + (nc.isAssignableFrom(ic) ? 1 : 0)
                + " reverse=" + (ic.isAssignableFrom(nc) ? 1 : 0)
                + " self=" + (ic.isAssignableFrom(ic) ? 1 : 0) + "\n");             // 1 0 1
        Class sup = ic.getSuperclass();
        Magic.printStr("Integer.getSuperclass==Number.class " + (sup == nc ? 1 : 0)
                + " name=" + sup.getName() + "\n");                                 // 1 java.lang.Number
        Magic.printStr("mirror identity=" + (("a".getClass() == "b".getClass()) ? 1 : 0) + "\n");   // 1

        Thread main1 = Thread.currentThread();
        Thread main2 = Thread.currentThread();
        Magic.printStr("main thread nonNull=" + (main1 != null ? 1 : 0)
                + " stable=" + (main1 == main2 ? 1 : 0) + "\n");                    // 1 1
        Worker w = new Worker();
        Thread t = new Thread(w, "worker-1");
        w.self = t;
        t.start();
        Thread.sleep(100L);                              // let the worker run (it sets spawnedOk)
        Magic.printStr("spawned currentThread==self " + spawnedOk
                + " getName=" + t.getName() + "\n");                                // 1 worker-1
    }
}

/** The spawned task's body: proves currentThread() inside the task is the Thread that started it. */
class Worker implements Runnable
{
    Thread self;                                         // the Thread that will run this worker

    public void run()
    {
        ReflectDemo.spawnedOk = Thread.currentThread() == self ? 1 : 0;
    }
}
