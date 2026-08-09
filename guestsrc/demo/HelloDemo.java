package demo;

/**
 * Minimal launched program: prints a banner and echoes its command-line args over System.out. A tiny,
 * fast-loading witness that the OS-style launcher runs an ordinary {@code main(String[])} with the args
 * from the {@code /etc/init} manifest (no VM hooks; stock String/System/PrintStream only).
 */
public class HelloDemo
{
    public static void main(String[] args)
    {
        System.out.println("hello from a launched main()");
        int i = 0;
        while (i < args.length)
        {
            System.out.println(args[i]);
            i = i + 1;
        }
        System.out.println("bye");
    }
}
