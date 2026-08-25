package app;

/**
 * The classpath-jar program. Nothing about it is joe-ng-specific: it is ordinary Java, compiled to a jar with
 * {@code jar --create --main-class app.Main}, dropped on the RAMFS at {@code /lib/app.jar}, and named by
 * {@code /etc/init}'s {@code classpath=} line. Neither this class nor {@link Greeting} is embedded in the
 * image — the on-metal loader inflates them out of the archive on demand.
 */
public class Main
{
    public static void main(String[] args)
    {
        System.out.println("hello from a jar");
        String who = "joe-ng";
        if (args.length > 0)
        {
            who = args[0];
        }
        Greeting g = new Greeting(who);
        System.out.println(g.text() + " (" + g.consonants() + " consonants)");
        int sum = 0;
        int i = 0;
        while (i <= 10)
        {
            sum += i;
            i += 1;
        }
        System.out.println("sum 0..10 = " + sum);
    }
}
