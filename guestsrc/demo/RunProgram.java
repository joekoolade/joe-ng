/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import jdk.internal.misc.MetalRunExit;

/**
 * Run a program handed over as bytes -- a {@code .jar} or a single {@code .class} -- in this VM, and capture what it
 * prints. The web server's {@code /run} endpoints ({@link RunPage}) deliver the bytes, by upload or by download.
 *
 * <p>The classes are defined through an ordinary {@code ClassLoader.defineClass}, ALL of them before {@code main}
 * runs, superclasses and interfaces first. The VM has ONE class namespace and resolves a reference by name, not by
 * asking the defining loader, so a class the jar holds but nobody has defined yet would be looked for in the image
 * and the class-path jar, and not found.
 *
 * <p>For the same reason a class name can be defined only once per boot. Running the SAME bytes again re-runs the
 * classes already loaded (their statics are kept, as on a hosted JVM that runs main twice); different bytes under
 * a name already loaded are refused, because the VM would go on resolving that name to the old class.
 *
 * <p>{@code System.out} and {@code System.err} are teed into one buffer while {@code main} runs, and still reach the
 * serial console. A thread the program starts keeps running after {@code main} returns; what it prints then goes to
 * the console only.
 *
 * <p>{@code System.exit} and {@code Runtime.halt} end the run, not the board ({@code jdk.internal.misc.MetalRunExit}):
 * the thread that calls them is unwound, and the run reports the status. The program's shutdown hooks are not run.
 */
final class RunProgram
{
    private RunProgram()
    {
    }

    /** The most output kept for the reply; the rest still reaches the console. */
    static final int MAX_OUTPUT = 1024 * 1024;

    /** Status: main returned. */
    static final int OK = 0;

    /** Status: main threw. */
    static final int THREW = 1;

    /** Status: the program could not be loaded (bad bytes, no main, a name clash); main never ran. */
    static final int REFUSED = 2;

    /** Status: a thread of the program called {@code System.exit} or {@code Runtime.halt} while main ran. */
    static final int EXITED = 3;

    /** SHA-256 of each class's bytes, by binary name, for every class a run defined this boot. */
    private static final Map<String, String> DEFINED = new HashMap<>();

    /** The class each of those names was defined as. A name is looked up here, never through a loader's
     *  delegation: on a hosted JVM the system loader cannot see a class another loader defined. */
    private static final Map<String, Class<?>> CLASSES = new HashMap<>();

    /** What one run did. */
    static final class Result
    {
        /** {@link #OK}, {@link #THREW}, {@link #REFUSED} or {@link #EXITED}. */
        int status;

        /** For {@link #EXITED}: what was called ("System.exit" or "Runtime.halt"), its status, and by which thread. */
        String exitHow;

        int exitStatus;

        String exitThread;

        /** The class whose main ran (or would have), or null when none was found. */
        String mainClass;

        /** How many classes this run defined; 0 for a re-run of classes already loaded. */
        int defined;

        /** Milliseconds spent defining the classes, and running main. */
        long defineMs;

        long runMs;

        /** Everything printed to System.out and System.err while main ran, then the run's own report. */
        String output;
    }

    /**
     * Run the program in {@code bytes}. {@code fileName} decides the kind (a name ending {@code .jar}, or bytes
     * starting with a zip signature, is a jar); {@code mainOverride}, when not null or empty, names the class to run
     * instead of the jar's {@code Main-Class}.
     */
    static Result run(String fileName, byte[] bytes, String mainOverride, String[] args)
    {
        Result r = new Result();
        Capture cap = new Capture();
        PrintStream report = new PrintStream(cap.buffer, true, StandardCharsets.UTF_8);
        try
        {
            Program p = isJar(fileName, bytes) ? readJar(bytes) : readClass(bytes);
            if (mainOverride != null && !mainOverride.isEmpty())
            {
                p.mainClass = mainOverride;
            }
            r.mainClass = p.mainClass;
            if (p.mainClass == null)
            {
                throw new Refused("no class to run: the jar's manifest has no Main-Class (give main=<class>)");
            }
            if (!p.classes.containsKey(p.mainClass))
            {
                throw new Refused("the program has no class " + p.mainClass);
            }
            long t0 = System.nanoTime();
            Class<?> main = define(p, r);
            r.defineMs = (System.nanoTime() - t0) / 1_000_000L;
            Method m = mainMethod(main);
            System.out.println("run: " + p.mainClass + ".main " + String.join(" ", args)
                    + (r.defined == 0 ? " (classes already loaded)" : " (" + r.defined + " classes defined)"));
            long t1 = System.nanoTime();
            cap.start();
            MetalRunExit.Run run = MetalRunExit.begin();     // System.exit in the program ends it, not the board
            try
            {
                m.invoke(null, (Object) args);
                r.status = OK;
            }
            catch (InvocationTargetException e)
            {
                if (!(e.getCause() instanceof MetalRunExit.Exit))
                {
                    r.status = THREW;
                    // The trace alone: "Exception in thread "main"" is the VM's own line for a program that DIED,
                    // and the probe harness stops a boot on it. This main threw into us, and the server goes on.
                    e.getCause().printStackTrace();
                }
            }
            finally
            {
                MetalRunExit.end();
                cap.stop();
                r.runMs = (System.nanoTime() - t1) / 1_000_000L;
            }
            if (run.exited())
            {
                r.status = EXITED;
                r.exitHow = run.how();
                r.exitStatus = run.status();
                r.exitThread = run.thread();
            }
            report.println();
            report.println("[" + ending(r) + "; defined " + r.defined + " classes in " + r.defineMs + " ms, ran "
                    + r.runMs + " ms]");
        }
        catch (Refused e)
        {
            r.status = REFUSED;
            report.println("not run: " + e.getMessage());
        }
        catch (Throwable e)
        {
            r.status = REFUSED;
            report.println("not run: " + e);
        }
        System.out.println("run: " + (r.mainClass == null ? fileName : r.mainClass) + " status " + r.status);
        r.output = cap.text();
        return r;
    }

    /** How main ended, for the report line. */
    static String ending(Result r)
    {
        if (r.status == EXITED)
        {
            return r.exitHow + "(" + r.exitStatus + ") called by thread \"" + r.exitThread + "\"";
        }
        return r.status == OK ? "main returned" : "main threw";
    }

    /** A program's classes (binary name -> classfile), its other entries, and the class to run. */
    private static final class Program
    {
        final Map<String, byte[]> classes = new LinkedHashMap<>();

        final Map<String, byte[]> resources = new HashMap<>();

        String mainClass;
    }

    /** A reason not to run, reported to the caller as it stands. */
    private static final class Refused extends Exception
    {
        Refused(String why)
        {
            super(why);
        }
    }

    private static boolean isJar(String fileName, byte[] b)
    {
        if (fileName != null && fileName.toLowerCase().endsWith(".jar"))
        {
            return true;
        }
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    private static Program readJar(byte[] bytes) throws IOException, Refused
    {
        Program p = new Program();
        JarInputStream jin = new JarInputStream(new ByteArrayInputStream(bytes), false);
        Manifest mf = jin.getManifest();
        if (mf != null)
        {
            String mc = mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (mc != null)
            {
                p.mainClass = mc.trim();
            }
        }
        JarEntry e = jin.getNextJarEntry();
        while (e != null)
        {
            if (!e.isDirectory())
            {
                String name = e.getName();
                byte[] data = jin.readAllBytes();
                if (name.endsWith(".class") && !name.equals("module-info.class") && !name.startsWith("META-INF/"))
                {
                    p.classes.put(name.substring(0, name.length() - 6).replace('/', '.'), data);
                }
                else
                {
                    p.resources.put(name, data);
                }
            }
            e = jin.getNextJarEntry();
        }
        jin.close();
        if (p.classes.isEmpty())
        {
            throw new Refused("the jar holds no classes (is it a jar?)");
        }
        return p;
    }

    private static Program readClass(byte[] bytes) throws Refused
    {
        Header h = Header.parse(bytes);
        Program p = new Program();
        p.classes.put(h.name, bytes);
        p.mainClass = h.name;
        return p;
    }

    /**
     * Define every class of {@code p}, supertypes first, and answer its main class. Classes already loaded with the
     * same bytes are re-used; any other class already known by name is refused before anything is defined.
     */
    private static Class<?> define(Program p, Result r) throws Exception
    {
        Map<String, Header> headers = new LinkedHashMap<>();
        Map<String, String> sums = new HashMap<>();
        List<String> fresh = new ArrayList<>();
        boolean anyReused = false;
        for (Map.Entry<String, byte[]> c : p.classes.entrySet())
        {
            Header h = Header.parse(c.getValue());
            if (!h.name.equals(c.getKey()))
            {
                throw new Refused(c.getKey() + " holds class " + h.name);
            }
            headers.put(h.name, h);
            String sum = sha256(c.getValue());
            sums.put(h.name, sum);
            String had = DEFINED.get(h.name);
            if (had != null)
            {
                if (!had.equals(sum))
                {
                    throw new Refused("class " + h.name + " was already loaded this boot with different bytes; "
                            + "the VM has one class namespace, so a changed program needs new class names or a reboot");
                }
                anyReused = true;
            }
            else if (exists(h.name))
            {
                throw new Refused("class " + h.name + " is already part of this VM (the image or its class path)");
            }
            else
            {
                fresh.add(h.name);
            }
        }
        if (anyReused && !fresh.isEmpty())
        {
            throw new Refused("the program mixes classes loaded by an earlier run with new ones (" + fresh.get(0)
                    + "); a changed program needs new class names or a reboot");
        }
        if (!fresh.isEmpty())
        {
            ProgramLoader loader = new ProgramLoader(p);
            List<String> order = new ArrayList<>();
            for (String n : fresh)
            {
                order(n, headers, order);
            }
            for (String n : order)
            {
                CLASSES.put(n, loader.defineOne(n, p.classes.get(n)));
                DEFINED.put(n, sums.get(n));
                r.defined = r.defined + 1;
            }
        }
        return CLASSES.get(p.mainClass);
    }

    /** Append {@code name} to {@code order} after its supertypes that the program itself holds. */
    private static void order(String name, Map<String, Header> headers, List<String> order)
    {
        Header h = headers.get(name);
        if (h == null || order.contains(name) || h.visiting)
        {
            return;
        }
        h.visiting = true;
        for (String s : h.supers)
        {
            order(s, headers, order);
        }
        h.visiting = false;
        order.add(name);
    }

    /** True when the VM already knows a class by this name. */
    private static boolean exists(String name)
    {
        try
        {
            Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return true;
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            return false;
        }
    }

    private static Method mainMethod(Class<?> c) throws Refused
    {
        Method m;
        try
        {
            m = c.getMethod("main", String[].class);
        }
        catch (NoSuchMethodException e)
        {
            throw new Refused(c.getName() + " has no public static void main(String[])");
        }
        if (!Modifier.isStatic(m.getModifiers()))
        {
            throw new Refused(c.getName() + ".main is not static");
        }
        return m;
    }

    private static String sha256(byte[] b) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The loader a run's classes are defined by. It also serves the jar's other entries as resources. */
    private static final class ProgramLoader extends ClassLoader
    {
        private final Program program;

        ProgramLoader(Program program)
        {
            super(ClassLoader.getSystemClassLoader());
            this.program = program;
        }

        Class<?> defineOne(String name, byte[] b)
        {
            return defineClass(name, b, 0, b.length);
        }

        @Override
        public InputStream getResourceAsStream(String name)
        {
            byte[] b = program.resources.get(name);
            if (b != null)
            {
                return new ByteArrayInputStream(b);
            }
            return super.getResourceAsStream(name);
        }
    }

    /** The names a classfile's header gives: its own, and its superclass and interfaces (dotted). */
    private static final class Header
    {
        String name;

        final List<String> supers = new ArrayList<>();

        boolean visiting;

        static Header parse(byte[] b) throws Refused
        {
            try
            {
                return parseOrThrow(b);
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                throw new Refused("not a class file: it ends inside its header");
            }
        }

        private static Header parseOrThrow(byte[] b) throws Refused
        {
            if (b.length < 10 || u4(b, 0) != 0xCAFEBABE)
            {
                throw new Refused("not a class file (no CAFEBABE)");
            }
            int count = u2(b, 8);
            int[] at = new int[count];
            int[] tag = new int[count];
            int p = 10;
            int i = 1;
            while (i < count)
            {
                int t = b[p] & 0xFF;
                tag[i] = t;
                at[i] = p + 1;
                int size;
                if (t == 1)
                {
                    size = 3 + u2(b, p + 1);
                }
                else if (t == 3 || t == 4 || t == 9 || t == 10 || t == 11 || t == 12 || t == 17 || t == 18)
                {
                    size = 5;
                }
                else if (t == 5 || t == 6)
                {
                    size = 9;
                }
                else if (t == 7 || t == 8 || t == 16 || t == 19 || t == 20)
                {
                    size = 3;
                }
                else if (t == 15)
                {
                    size = 4;
                }
                else
                {
                    throw new Refused("not a class file: constant pool tag " + t);
                }
                p = p + size;
                if (t == 5 || t == 6)
                {
                    i = i + 1;
                }
                i = i + 1;
            }
            Header h = new Header();
            h.name = className(b, at, tag, u2(b, p + 2));
            int sup = u2(b, p + 4);
            if (sup != 0)
            {
                h.supers.add(className(b, at, tag, sup));
            }
            int ni = u2(b, p + 6);
            int k = 0;
            while (k < ni)
            {
                h.supers.add(className(b, at, tag, u2(b, p + 8 + 2 * k)));
                k = k + 1;
            }
            return h;
        }

        private static String className(byte[] b, int[] at, int[] tag, int index) throws Refused
        {
            if (index <= 0 || index >= at.length || tag[index] != 7)
            {
                throw new Refused("not a class file: bad class index " + index);
            }
            int utf = u2(b, at[index]);
            if (utf <= 0 || utf >= at.length || tag[utf] != 1)
            {
                throw new Refused("not a class file: bad name index " + utf);
            }
            int len = u2(b, at[utf]);
            return new String(b, at[utf] + 2, len, StandardCharsets.UTF_8).replace('/', '.');
        }

        private static int u2(byte[] b, int p)
        {
            return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        }

        private static int u4(byte[] b, int p)
        {
            return (u2(b, p) << 16) | u2(b, p + 2);
        }
    }

    /**
     * Tees System.out and System.err into one buffer between {@link #start} and {@link #stop}. The originals still
     * get every byte, so the serial console shows the program as it runs.
     */
    private static final class Capture
    {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private boolean truncated;

        private PrintStream savedOut;

        private PrintStream savedErr;

        void start()
        {
            savedOut = System.out;
            savedErr = System.err;
            System.setOut(new PrintStream(new Tee(savedOut), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new Tee(savedErr), true, StandardCharsets.UTF_8));
        }

        void stop()
        {
            System.out.flush();
            System.err.flush();
            System.setOut(savedOut);
            System.setErr(savedErr);
        }

        String text()
        {
            String s = buffer.toString(StandardCharsets.UTF_8);
            if (truncated)
            {
                s = s + "\n[output truncated at " + MAX_OUTPUT + " bytes; the console has all of it]\n";
            }
            return s;
        }

        /** One of the two streams: every byte to the original, and to the shared buffer while it has room. */
        private final class Tee extends OutputStream
        {
            private final OutputStream original;

            Tee(OutputStream original)
            {
                this.original = original;
            }

            @Override
            public void write(int b) throws IOException
            {
                original.write(b);
                keep(new byte[] { (byte) b }, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
                original.write(b, off, len);
                keep(b, off, len);
            }

            @Override
            public void flush() throws IOException
            {
                original.flush();
            }

            private void keep(byte[] b, int off, int len)
            {
                synchronized (buffer)
                {
                    int room = MAX_OUTPUT - buffer.size();
                    if (len > room)
                    {
                        truncated = true;
                        len = Math.max(0, room);
                    }
                    buffer.write(b, off, len);
                }
            }
        }
    }
}
