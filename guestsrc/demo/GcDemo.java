package demo;

/**
 * The GC milestone: allocate FAR more than the heap holds — ~640 MB of churn through core 0's ~192 MB
 * arena — so the program only completes if allocation pressure triggers real collections
 * ({@code Heap.alloc} -> {@code Magic.gc} -> {@code VM.gcCollect}) and the freed blocks get reused.
 * A small rotating live set must survive every collection intact: each kept block carries its round
 * number (first two bytes) and a derived checksum (last byte), verified at the end. Ordinary Java —
 * stock library only, no VM hooks; the batch driver prints the collection count afterwards as evidence.
 */
public class GcDemo
{

    public static void main(String[] args)
    {
        int block = 65536;                              // 64 KB per allocation
        int rounds = 10000;                             // ~640 MB total churn
        int liveSlots = 32;                             // ~2 MB held live at any moment
        Object[] live = new Object[liveSlots];
        int[] liveRound = new int[liveSlots];
        int keep = 0;                                   // countdown to the next kept block (avoids %)
        int slot = 0;
        int dot = 0;
        int i = 0;
        while (i < rounds)
        {
            byte[] b = new byte[block];
            b[0] = (byte) i;                            // round number, low byte
            b[1] = (byte) (i >> 8);                     //   ...high byte
            b[block - 1] = (byte) (i * 31 + 7);         // checksum derived from the round
            if (keep == 0)
            {
                live[slot] = b;                         // every 100th block survives (rotating slots)
                liveRound[slot] = i;
                slot += 1;
                if (slot == liveSlots)
                {
                    slot = 0;
                }
                keep = 100;
            }
            keep -= 1;
            dot += 1;
            if (dot == 1000)
            {
                System.out.print(".");                  // heartbeat: 10 dots across the run
                dot = 0;
            }
            i += 1;
        }
        System.out.println();

        int held = 0;
        int intact = 0;
        int j = 0;
        while (j < liveSlots)
        {
            byte[] b = (byte[]) live[j];
            if (b != null)
            {
                held += 1;
                int round = (b[0] & 0xff) | ((b[1] & 0xff) << 8);
                // Mask BOTH sides of the checksum compare: joe-ng's baload zero-extends where the JVM
                // sign-extends, so an unmasked byte compare diverges for checksum values >= 128.
                boolean ok = round == liveRound[j]
                        && b.length == block
                        && (b[block - 1] & 0xff) == ((round * 31 + 7) & 0xff)
                        && b[2] == 0;                   // middle stayed zero (untouched by reuse/zeroing)
                if (ok)
                {
                    intact += 1;
                }
                else
                {
                    System.out.print("  slot " + j + " exp=" + liveRound[j] + " got=" + round);
                    System.out.println(" tail=" + (b[block - 1] & 0xff) + " mid=" + (b[2] & 0xff));
                }
            }
            j += 1;
        }
        System.out.println("churnMB=" + (rounds / 16) + " live=" + held + " intact=" + intact);
    }
}
