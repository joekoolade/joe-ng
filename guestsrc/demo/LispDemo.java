package demo;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * The long-running-program milestone: a small Lisp interpreter — ordinary Java, stock library only, no VM
 * hooks — running on the bare-metal VM. It reads its program from the RAMFS ({@code new String(readAllBytes)},
 * the charset closure), tokenizes with {@code StringBuilder}, parses to {@code ArrayList} trees, and evaluates
 * with lexically-scoped closures over {@code HashMap} environment frames. Every {@code (fib n)} call allocates
 * frames/boxes/argument lists, so the churn loop pushes hundreds of MB through the heap and the program only
 * completes — with every iteration still correct — because the collector reclaims mid-computation.
 *
 * <p>Special forms: {@code define}, {@code lambda}, {@code if}, {@code quote}. Builtins: {@code + - * < =}
 * (integers; truth is any non-zero Integer). Differential check: the SAME class runs on the host JDK
 * ({@code java -cp out demo.LispDemo ramfs/data/prog.lisp 600}) and must print byte-identical output.
 */
public class LispDemo
{
    static int pos;                                     // parser cursor into the token list

    public static void main(String[] args)
    {
        String path = args.length > 0 ? args[0] : "/data/prog.lisp";
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 600;

        String src;
        try
        {
            FileInputStream in = new FileInputStream(path);
            src = new String(in.readAllBytes());
            in.close();
        }
        catch (IOException e)
        {
            System.out.println("cannot read " + path + ": " + e.getMessage());
            return;
        }

        LispFrame global = new LispFrame(null);
        bind(global, "+");
        bind(global, "-");
        bind(global, "*");
        bind(global, "<");
        bind(global, "=");

        ArrayList tokens = tokenize(src);
        pos = 0;
        int forms = 0;
        while (pos < tokens.size())
        {
            eval(parse(tokens), global);                // top-level defines from the RAMFS program
            forms += 1;
        }
        System.out.println("lisp: loaded " + forms + " forms from " + path);

        System.out.println("(fact 10) = " + run("(fact 10)", global));          // 3628800
        System.out.println("(fib 18) = " + run("(fib 18)", global));            // 2584
        System.out.println("(sum 100 0) = " + run("(sum 100 0)", global));      // 5050
        System.out.println("(twice inc 40) = " + run("(twice inc 40)", global)); // 42

        // The long run: every (fib 15) evaluation allocates ~2000 environment frames + boxes + arg lists;
        // hundreds of iterations churn far past the arena, so collections MUST happen mid-computation and
        // every result afterwards must still be correct (live interpreter state survives each GC).
        Object expr = parseOne("(fib 15)");
        int stable = 1;
        int dot = 0;
        int i = 0;
        while (i < iterations)
        {
            Object r = eval(expr, global);
            if (((Integer) r).intValue() != 610)
            {
                stable = 0;
            }
            dot += 1;
            if (dot == 60)
            {
                System.out.print(".");
                dot = 0;
            }
            i += 1;
        }
        System.out.println();
        System.out.println("lisp: evals=" + iterations + " result=610 stable=" + stable);
    }

    /** Bind a builtin operator symbol to itself (eval resolves it; apply dispatches on the String). */
    private static void bind(LispFrame env, String op)
    {
        env.vars.put(op, op);
    }

    /** Parse and evaluate one expression in {@code env}. */
    private static Object run(String s, LispFrame env)
    {
        return eval(parseOne(s), env);
    }

    private static Object parseOne(String s)
    {
        ArrayList t = tokenize(s);
        pos = 0;
        return parse(t);
    }

    private static ArrayList tokenize(String src)
    {
        ArrayList t = new ArrayList();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < src.length())
        {
            char c = src.charAt(i);
            if (c == '(' || c == ')')
            {
                if (cur.length() > 0)
                {
                    t.add(cur.toString());
                    cur = new StringBuilder();
                }
                t.add(c == '(' ? "(" : ")");
            }
            else if (c == ' ' || c == '\n' || c == '\r' || c == '\t')
            {
                if (cur.length() > 0)
                {
                    t.add(cur.toString());
                    cur = new StringBuilder();
                }
            }
            else
            {
                cur.append(c);
            }
            i += 1;
        }
        if (cur.length() > 0)
        {
            t.add(cur.toString());
        }
        return t;
    }

    private static Object parse(ArrayList tokens)
    {
        String tok = (String) tokens.get(pos);
        pos += 1;
        if (tok.equals("("))
        {
            ArrayList list = new ArrayList();
            while (!tokens.get(pos).equals(")"))
            {
                list.add(parse(tokens));
            }
            pos += 1;                                   // consume ')'
            return list;
        }
        char c0 = tok.charAt(0);
        if ((c0 >= '0' && c0 <= '9') || (c0 == '-' && tok.length() > 1))
        {
            return Integer.valueOf(Integer.parseInt(tok));
        }
        return tok;                                     // a symbol
    }

    static Object eval(Object x, LispFrame env)
    {
        if (x instanceof String)
        {
            return env.lookup((String) x);
        }
        if (x instanceof Integer)
        {
            return x;
        }
        ArrayList form = (ArrayList) x;
        Object head = form.get(0);
        if (head instanceof String)
        {
            String op = (String) head;
            if (op.equals("if"))
            {
                Object cond = eval(form.get(1), env);
                boolean truth = ((Integer) cond).intValue() != 0;
                return eval(truth ? form.get(2) : form.get(3), env);
            }
            if (op.equals("define"))
            {
                env.vars.put((String) form.get(1), eval(form.get(2), env));
                return null;
            }
            if (op.equals("lambda"))
            {
                return new LispClosure((ArrayList) form.get(1), form.get(2), env);
            }
            if (op.equals("quote"))
            {
                return form.get(1);
            }
        }
        Object f = eval(head, env);
        ArrayList argv = new ArrayList();
        int i = 1;
        while (i < form.size())
        {
            argv.add(eval(form.get(i), env));
            i += 1;
        }
        return apply(f, argv);
    }

    static Object apply(Object f, ArrayList argv)
    {
        if (f instanceof String)                        // a builtin operator
        {
            String op = (String) f;
            int a = ((Integer) argv.get(0)).intValue();
            int b = ((Integer) argv.get(1)).intValue();
            if (op.equals("+"))
            {
                return Integer.valueOf(a + b);
            }
            if (op.equals("-"))
            {
                return Integer.valueOf(a - b);
            }
            if (op.equals("*"))
            {
                return Integer.valueOf(a * b);
            }
            if (op.equals("<"))
            {
                return Integer.valueOf(a < b ? 1 : 0);
            }
            return Integer.valueOf(a == b ? 1 : 0);     // "="
        }
        LispClosure c = (LispClosure) f;
        LispFrame env = new LispFrame(c.env);
        int i = 0;
        while (i < c.params.size())
        {
            env.vars.put((String) c.params.get(i), argv.get(i));
            i += 1;
        }
        return eval(c.body, env);
    }
}

/** A lexical environment frame: bindings + a parent to search outward. */
class LispFrame
{
    final HashMap vars = new HashMap();
    final LispFrame parent;

    LispFrame(LispFrame parent)
    {
        this.parent = parent;
    }

    Object lookup(String name)
    {
        LispFrame f = this;
        while (f != null)
        {
            Object v = f.vars.get(name);
            if (v != null)
            {
                return v;
            }
            f = f.parent;
        }
        return null;
    }
}

/** A lambda: parameter symbols + body expression + the defining environment (lexical scope). */
class LispClosure
{
    final ArrayList params;
    final Object body;
    final LispFrame env;

    LispClosure(ArrayList params, Object body, LispFrame env)
    {
        this.params = params;
        this.body = body;
        this.env = env;
    }
}
