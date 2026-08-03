package demo;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The real-program milestone: a classic word-frequency counter written as ORDINARY Java — stock library
 * only, no VM hooks (no {@code magic.Magic} import), entered through a real {@code main(String[])} with
 * arguments the VM passes in. Composes the whole stock-java.base arc in one program: {@code FileInputStream}
 * over the embedded RAMFS (M3), {@code StringBuilder}, {@code toLowerCase}/{@code replace}/{@code split}/
 * {@code trim} (#41/#42), {@code HashMap} + Integer autoboxing (#33), {@code ArrayList} + entrySet views
 * (#34), {@code System.out.println} (M2), and checked-exception handling with {@code getMessage()}.
 *
 * <p>Differential check: the SAME class runs on the host JDK
 * ({@code java -cp out demo.WordCount ramfs/data/sample.txt 3}) and must print byte-identical output.
 */
public class WordCount
{
    public static void main(String[] args)
    {
        String path = args.length > 0 ? args[0] : "/data/sample.txt";
        int topN = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        String text;
        try
        {
            FileInputStream in = new FileInputStream(path);
            byte[] all = in.readAllBytes();
            in.close();
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < all.length)
            {
                sb.append((char) (all[i] & 0xff));      // Latin1 bytes -> chars (avoids the charset closure)
                i += 1;
            }
            text = sb.toString();
        }
        catch (IOException e)
        {
            System.out.println("cannot read " + path + ": " + e.getMessage());
            return;
        }

        String[] words = text.toLowerCase().replace('\n', ' ').split(" ");
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (String w : words)
        {
            String word = w.trim();
            if (word.isEmpty())
            {
                continue;
            }
            total += 1;
            Integer prev = counts.get(word);
            counts.put(word, prev == null ? 1 : prev + 1);
        }
        System.out.println("words=" + total + " distinct=" + counts.size());

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
        {
            keys.add(e.getKey());
        }
        int rank = 1;
        while (rank <= topN && !keys.isEmpty())
        {
            String best = null;
            int bestN = -1;
            for (String k : keys)
            {
                int n = counts.get(k);
                if (n > bestN || (n == bestN && k.compareTo(best) < 0))
                {
                    best = k;                            // highest count; ties broken alphabetically
                    bestN = n;
                }
            }
            System.out.println(rank + ". " + best + " " + bestN);
            keys.remove(best);
            rank += 1;
        }
    }
}
