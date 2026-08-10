package demo;

import magic.Magic;

/** Address-trap smoke test: a load from an unmapped address raises an EL1 data abort, which the fault
 *  handler converts into a NullPointerException thrown at the faulting instruction -- a real, catchable
 *  Java exception (this demo catches it and keeps running). */
public class AddrTrapDemo
{
    public static void main(String[] args)
    {
        long bad = 0x8000000000L;             // 512 GiB -- beyond the 4 GiB board, unmapped
        try
        {
            long x = Magic.load64(bad);       // data abort -> NullPointerException, thrown here
            System.out.println("no trap? " + x);
        }
        catch (NullPointerException e)
        {
            System.out.println("caught address trap as NPE, still alive");
        }
        System.out.println("done");
    }
}
