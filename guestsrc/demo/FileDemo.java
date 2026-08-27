package demo;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import magic.Magic;

/**
 * M3: {@code java.io} over the embedded read-only RAMFS. Opens files the writer baked into the image
 * ({@code ramfs/} -> the {@code VM.fileDir} table) through the guest {@link FileInputStream} overlay:
 * {@code available}/{@code readAllBytes} (content checked byte-for-byte), single-byte {@code read()} to
 * end-of-file, {@code skip}, a second file, and a missing path -> real {@code FileNotFoundException}
 * (stock class; its message rides the {@code Throwable.detailMessage} chain).
 */
public class FileDemo
{
    public static void main(String[] args)
    {
        try
        {
            FileInputStream in = new FileInputStream("/etc/motd");
            int avail = in.available();
            byte[] all = in.readAllBytes();
            String expect = "hello from ramfs\n";
            boolean ok = all.length == expect.length();
            int i = 0;
            while (ok && i < all.length)
            {
                if ((all[i] & 0xff) != expect.charAt(i))
                {
                    ok = false;
                }
                i += 1;
            }
            Magic.printStr("motd avail=" + avail + " len=" + all.length
                    + " contentOk=" + (ok ? 1 : 0) + " afterAvail=" + in.available() + "\n");   // 17,17,1,0

            FileInputStream in2 = new FileInputStream("/etc/motd");
            int h = in2.read();                          // 'h' = 104
            long skipped = in2.skip(4L);                 // over "ello"
            int sp = in2.read();                         // ' ' = 32
            int n = 0;
            while (in2.read() >= 0)                      // drain to EOF
            {
                n += 1;
            }
            int eof = in2.read();                        // past EOF -> -1
            Magic.printStr("read h=" + h + " skip=" + (int) skipped + " next=" + sp
                    + " drained=" + n + " eof=" + eof + "\n");                                  // 104,4,32,11,-1

            FileInputStream in3 = new FileInputStream("/data/nums.txt");
            Magic.printStr("nums.txt avail=" + in3.available() + " first=" + in3.read() + "\n"); // 9, 49 ('1')
        }
        catch (FileNotFoundException e)
        {
            Magic.printStr("unexpected FileNotFoundException\n");
        }

        try
        {
            FileInputStream missing = new FileInputStream("/no/such/file");
            Magic.printStr("missing opened?! avail=" + missing.available() + "\n");
        }
        catch (FileNotFoundException e)
        {
            Magic.printStr("missing -> FileNotFoundException: " + e.getMessage() + "\n");        // /no/such/file
        }
    }
}
