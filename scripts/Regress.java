/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-21
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * The probe regression: every probe in {@code test/regress/probes.txt} booted on QEMU and its output compared with
 * {@code test/regress/expected/<Probe>.out} -- the host JVM's output, committed so it cannot be purged from /tmp.
 *
 * <p>Usage, from the repo root: {@code java scripts/Regress.java [-j N] [--solo | --batch] [Probe ...]}. With no
 * probe names it runs the whole list. {@code -j} is how many QEMUs run at once (default 4); {@code --solo} boots
 * every probe on its own, the slow form, for when batching itself is in question; {@code --no-make} skips the build (another run just did it); {@code --batch} runs the named
 * probes as ONE batch in the order given, solo marks ignored and no solo reruns -- the tool for narrowing a
 * failure that only a batch shows down to the probe that sets it up.
 *
 * <p>WHY BATCHES: a boot costs ~40 s before main runs and a probe runs in seconds, so one image per probe spent
 * most of an hour booting. Probes not marked {@code solo} run several to a boot through {@code ProbeBatch}. A probe
 * that does not compare IDENTICAL in its batch -- or never ran because an earlier one halted the VM -- is rerun ON
 * ITS OWN, and the solo run is the verdict, so batching can never fail a probe. It can only MASK a failure through
 * shared state (a class already initialized by an earlier probe), which is what {@code solo} in the list is for.
 *
 * <p>Images are built with {@code BuildRuntimeImage --init}, so {@code ramfs/etc/init} is never rewritten and
 * several images build from one checkout. Logs go to {@code /tmp/joeng-regress/<time>/}.
 */
public final class Regress
{
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path LIST = ROOT.resolve("test/regress/probes.txt");
    private static final Path EXPECTED = ROOT.resolve("test/regress/expected");

    /** Boot-log lines that are the VM's own reporting, not the probe's output (the old runners' grep filter). */
    private static final Pattern NOISE = Pattern.compile("^launch|^  batch|INITIALIZER RUNNING|^  \\.\\.\\.|^$"
            + "|UNREGISTERED SUPER|arrayadopt|NULL CLASS LITERAL|TRAP-WIRED|^\\[main|^gc:|^qemu|bakeMemosDropped"
            + "|sync: static|ARRAY ELEMENT|^  iface tier|UNRESOLVED STATIC|CTOR SKIPPED|^    tried|HUGE body"
            + "|^code arena");

    /** A solo run is over: the program returned, or the VM stopped it. */
    private static final Pattern SOLO_END = Pattern.compile("\\[main returned normally\\]|^Exception in thread"
            + "|DENYLIST TRAP|^ *FAULT|BOOT RE-ENTERED|LINK FAILED|JIT unsupported|TAIL TARGET IS 0|Halting\\."
            + "|LAZY INDEX OUT OF RANGE|CAP EXCEEDED");

    /** A batch is over. Not "Exception in thread": a probe's own background thread may print that and go on. */
    private static final Pattern BATCH_END = Pattern.compile("^@@@ ProbeBatch end|\\[main returned normally\\]"
            + "|DENYLIST TRAP|^ *FAULT|BOOT RE-ENTERED|TAIL TARGET IS 0|Halting\\.|LAZY INDEX OUT OF RANGE|CAP EXCEEDED");

    private static final String MARK = "@@@ ProbeBatch ";
    private static final int MAX_PER_BATCH = 25;

    /** One line of probes.txt: the probe, whether it must boot alone, its RAMFS classpath, its pass rule. */
    private record Probe(String name, boolean solo, String cp, String gate)
    {
    }

    /** One QEMU boot: a single probe, or a batch of them sharing a classpath. */
    private record Job(String tag, List<Probe> probes, boolean batch)
    {
    }

    private static Path runDir;
    private static final long START = System.nanoTime();

    public static void main(String[] args) throws Exception
    {
        int jobs = 4;
        boolean allSolo = false;
        boolean oneBatch = false;
        boolean doMake = true;
        List<String> wanted = new ArrayList<>();
        int i = 0;
        while (i < args.length)
        {
            if (args[i].equals("-j") && i + 1 < args.length)
            {
                jobs = Integer.parseInt(args[i + 1]);
                i += 2;
            }
            else if (args[i].equals("--solo"))
            {
                allSolo = true;
                i += 1;
            }
            else if (args[i].equals("--no-make"))
            {
                doMake = false;                                   // out/ is already built (parallel runs)
                i += 1;
            }
            else if (args[i].equals("--batch"))
            {
                oneBatch = true;
                i += 1;
            }
            else
            {
                wanted.add(args[i]);
                i += 1;
            }
        }

        Map<String, Probe> listed = readList();
        List<Probe> probes = new ArrayList<>();
        if (wanted.isEmpty())
        {
            probes.addAll(listed.values());
        }
        else
        {
            for (String w : wanted)
            {
                Probe p = listed.get(w);
                if (p == null)
                {
                    die(w + " is not in " + LIST);
                }
                probes.add(p);
            }
        }
        for (Probe p : probes)
        {
            if (p.gate() == null && !Files.isRegularFile(expectedOf(p)))
            {
                die("no expected output for " + p.name() + " (" + expectedOf(p) + ")");
            }
        }

        runDir = Path.of("/tmp/joeng-regress/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + ProcessHandle.current().pid());
        Files.createDirectories(runDir);
        log("logs in " + runDir);

        Path init = ROOT.resolve("ramfs/etc/init");
        byte[] initBefore = Files.exists(init) ? Files.readAllBytes(init) : null;
        if (doMake)
        {
            log("make build jdktests plugins");
        }
        if (doMake && run(List.of("make", "build", "jdktests", "plugins"), runDir.resolve("make.log")) != 0)
        {
            die("make failed; see " + runDir.resolve("make.log"));
        }
        byte[] initAfter = Files.exists(init) ? Files.readAllBytes(init) : null;
        if (!Arrays.equals(initBefore, initAfter))
        {
            if (initBefore != null)
            {
                Files.write(init, initBefore);
            }
            log("WARNING: make changed ramfs/etc/init; restored it");
        }

        List<Job> plan = oneBatch ? List.of(new Job("batch1", List.copyOf(probes), true)) : plan(probes, allSolo, jobs);
        int batched = 0;
        int batches = 0;
        for (Job j : plan)
        {
            if (j.batch())
            {
                batched += j.probes().size();
                batches += 1;
            }
        }
        log(probes.size() + " probes: " + batched + " in " + batches + " batches, " + (probes.size() - batched)
                + " solo; " + jobs + " at a time");

        Map<String, String> verdict = new LinkedHashMap<>();
        for (Probe p : probes)
        {
            verdict.put(p.name(), "NOT RUN");
        }
        runAll(plan, jobs, verdict);

        // A batch miss is not a verdict: rerun it alone, and let that decide.
        List<Job> retry = new ArrayList<>();
        for (Job j : plan)
        {
            if (!j.batch() || oneBatch)
            {
                continue;
            }
            for (Probe p : j.probes())
            {
                if (!verdict.get(p.name()).equals("IDENTICAL"))
                {
                    log(p.name() + " " + verdict.get(p.name()) + " in its batch; rerunning it alone");
                    retry.add(new Job(p.name(), List.of(p), false));
                }
            }
        }
        List<String> batchOnly = new ArrayList<>();
        if (!retry.isEmpty())
        {
            runAll(retry, jobs, verdict);
            for (Job j : retry)
            {
                if (verdict.get(j.tag()).equals("IDENTICAL"))
                {
                    batchOnly.add(j.tag());
                }
            }
        }

        int pass = 0;
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, String> e : verdict.entrySet())
        {
            if (e.getValue().equals("IDENTICAL") || e.getValue().equals("GATE OK"))
            {
                pass += 1;
            }
            else
            {
                failed.add(e.getKey() + " " + e.getValue());
            }
        }
        log("RESULT: " + pass + " of " + verdict.size() + " pass" + (failed.isEmpty() ? "" : "; FAILED: " + failed));
        if (!batchOnly.isEmpty())
        {
            log("differed only in a batch, identical alone (mark solo in probes.txt if this repeats): " + batchOnly);
        }
        log("elapsed " + elapsed());
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    /** Parse probes.txt: {@code <Probe> [solo] [cp=<ramfs path>] [gate=<line the output must contain>]}. */
    private static Map<String, Probe> readList() throws IOException
    {
        Map<String, Probe> m = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(LIST))
        {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty())
            {
                continue;
            }
            String[] f = line.split("\\s+", 2);
            String rest = f.length > 1 ? f[1] : "";
            boolean solo = false;
            String cp = null;
            String gate = null;
            for (String opt : rest.isEmpty() ? new String[0] : rest.split("\\s+(?=solo\\b|cp=|gate=)"))
            {
                if (opt.equals("solo"))
                {
                    solo = true;
                }
                else if (opt.startsWith("cp="))
                {
                    cp = opt.substring(3);
                }
                else if (opt.startsWith("gate="))
                {
                    gate = opt.substring(5);
                }
                else
                {
                    die("probes.txt: cannot read '" + raw + "'");
                }
            }
            if (m.put(f[0], new Probe(f[0], solo, cp, gate)) != null)
            {
                die("probes.txt lists " + f[0] + " twice");
            }
        }
        return m;
    }

    /**
     * Batches first (they are the long jobs), each probe sharing a classpath with its batch-mates, the batchable
     * probes spread so every QEMU slot gets work; then the solo probes.
     */
    private static List<Job> plan(List<Probe> probes, boolean allSolo, int jobs)
    {
        Map<String, List<Probe>> byCp = new LinkedHashMap<>();
        List<Job> solos = new ArrayList<>();
        for (Probe p : probes)
        {
            if (allSolo || p.solo())
            {
                solos.add(new Job(p.name(), List.of(p), false));
            }
            else
            {
                byCp.computeIfAbsent(p.cp() == null ? "" : p.cp(), k -> new ArrayList<>()).add(p);
            }
        }
        List<Job> plan = new ArrayList<>();
        int n = 0;
        for (List<Probe> group : byCp.values())
        {
            int count = Math.max(1, Math.max((group.size() + MAX_PER_BATCH - 1) / MAX_PER_BATCH,
                    Math.min(jobs, group.size() / 4)));
            int k = 0;
            while (k < count)
            {
                int from = group.size() * k / count;
                int to = group.size() * (k + 1) / count;
                List<Probe> part = group.subList(from, to);
                if (part.size() == 1)
                {
                    solos.add(0, new Job(part.get(0).name(), List.of(part.get(0)), false));
                }
                else if (!part.isEmpty())
                {
                    n += 1;
                    plan.add(new Job("batch" + n, List.copyOf(part), true));
                }
                k += 1;
            }
        }
        plan.addAll(solos);
        return plan;
    }

    private static void runAll(List<Job> plan, int jobs, Map<String, String> verdict) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<Map<String, String>>> done = new ArrayList<>();
        for (Job j : plan)
        {
            done.add(pool.submit(() -> runJob(j)));
        }
        for (Future<Map<String, String>> f : done)
        {
            Map<String, String> r = f.get();
            synchronized (verdict)
            {
                verdict.putAll(r);
            }
        }
        pool.shutdown();
    }

    /** Build the job's image, boot it, and judge every probe in it. */
    private static Map<String, String> runJob(Job job) throws Exception
    {
        String tag = job.tag() + (job.batch() ? "" : "-solo");
        Map<String, String> out = new LinkedHashMap<>();
        Probe first = job.probes().get(0);
        StringBuilder init = new StringBuilder();
        if (job.batch())
        {
            init.append("main=ProbeBatch\nargs=");
            for (Probe p : job.probes())
            {
                init.append(p.name()).append(' ');
            }
            init.setLength(init.length() - 1);
            init.append('\n');
        }
        else
        {
            init.append("main=").append(first.name()).append('\n');
        }
        if (first.cp() != null)
        {
            init.append("classpath=").append(first.cp()).append('\n');
        }
        Path initFile = runDir.resolve(tag + ".init");
        Path img = runDir.resolve(tag + ".img");
        Path logFile = runDir.resolve(tag + ".log");
        Files.writeString(initFile, init);
        int rc = run(List.of("java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-opens",
                "java.base/java.util=ALL-UNNAMED", "-cp", "out", "writer.BuildRuntimeImage", "out", img.toString(),
                "--init", initFile.toString()), runDir.resolve(tag + ".build"));
        if (rc != 0)
        {
            for (Probe p : job.probes())
            {
                out.put(p.name(), "IMAGE BUILD FAILED");
            }
            log(tag + ": image build failed, see " + runDir.resolve(tag + ".build"));
            return out;
        }

        long ceiling = job.batch() ? 600 + 120L * job.probes().size() : 900;
        long t0 = System.nanoTime();
        Process q = new ProcessBuilder("qemu-system-aarch64", "-M", "raspi4b", "-kernel", img.toString(),
                "-serial", "null", "-serial", "stdio", "-display", "none", "-no-reboot")
                .redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        Pattern end = job.batch() ? BATCH_END : SOLO_END;
        boolean finished = false;
        while (!finished && (System.nanoTime() - t0) / 1_000_000_000L < ceiling && q.isAlive())
        {
            Thread.sleep(2000);
            for (String line : lines(logFile))
            {
                if (end.matcher(line).find())
                {
                    finished = true;
                    break;
                }
            }
        }
        if (finished)
        {
            Thread.sleep(3000);                                   // let the tail (a trap's report) reach the log
        }
        q.destroy();
        if (!q.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        {
            q.destroyForcibly();
        }
        Files.deleteIfExists(img);
        long secs = (System.nanoTime() - t0) / 1_000_000_000L;

        List<String> all = lines(logFile);
        String main = job.batch() ? "ProbeBatch" : first.name();
        int from = -1;
        int k = 0;
        while (k < all.size())
        {
            if (all.get(k).equals("launch " + main))
            {
                from = k;
                break;
            }
            k += 1;
        }
        List<String> shown = new ArrayList<>();
        if (from >= 0)
        {
            for (String line : all.subList(from, all.size()))
            {
                if (!NOISE.matcher(line).find())
                {
                    shown.add(line);
                }
            }
        }

        Map<String, List<String>> sections = new LinkedHashMap<>();
        if (!job.batch())
        {
            sections.put(first.name(), shown);
        }
        else
        {
            List<String> cur = null;
            for (String line : shown)
            {
                if (line.startsWith(MARK) && line.length() > MARK.length() && line.indexOf(' ', MARK.length()) < 0)
                {
                    String name = line.substring(MARK.length());
                    if (name.equals("end"))
                    {
                        cur = null;
                    }
                    else
                    {
                        cur = new ArrayList<>();
                        sections.put(name, cur);
                    }
                }
                else if (cur != null)
                {
                    cur.add(line);
                }
            }
            if (cur != null)
            {
                sections.put(lastKey(sections), null);            // started, never finished: the VM stopped in it
            }
        }

        StringBuilder report = new StringBuilder(tag + " (" + secs + "s" + (finished ? "" : ", NO END MARKER") + "):");
        for (Probe p : job.probes())
        {
            List<String> got = sections.get(p.name());
            String v;
            if (!sections.containsKey(p.name()))
            {
                v = "NOT REACHED";
            }
            else if (got == null)
            {
                v = "DID NOT FINISH";
            }
            else
            {
                Files.write(runDir.resolve(p.name() + (job.batch() ? ".batch" : ".solo") + ".metal"), got);
                if (p.gate() != null)
                {
                    v = got.stream().anyMatch(l -> l.contains(p.gate())) ? "GATE OK" : "GATE FAILED";
                }
                else
                {
                    List<String> want = Files.readAllLines(expectedOf(p), StandardCharsets.ISO_8859_1);
                    v = want.equals(got) ? "IDENTICAL" : "DIFFERS";
                }
            }
            out.put(p.name(), v.equals("GATE OK") ? "IDENTICAL" : v);
            if (!v.equals("IDENTICAL"))
            {
                report.append(' ').append(p.name()).append('=').append(v);
            }
        }
        long ok = out.values().stream().filter(s -> s.equals("IDENTICAL")).count();
        log(report + " " + ok + "/" + job.probes().size() + " ok");
        return out;
    }

    private static Path expectedOf(Probe p)
    {
        return EXPECTED.resolve(p.name() + ".out");
    }

    /** The log as lines, carriage returns dropped (the UART writes CRLF). Read as Latin-1: it is binary-ish. */
    private static List<String> lines(Path f) throws IOException
    {
        if (!Files.exists(f))
        {
            return List.of();
        }
        String s = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).replace("\r", "");
        List<String> l = new ArrayList<>(Arrays.asList(s.split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty())
        {
            l.remove(l.size() - 1);
        }
        return l;
    }

    private static String lastKey(Map<String, List<String>> m)
    {
        String last = null;
        for (String k : m.keySet())
        {
            last = k;
        }
        return last;
    }

    private static int run(List<String> cmd, Path logTo) throws Exception
    {
        Process p = new ProcessBuilder(cmd).directory(ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(logTo.toFile()).start();
        return p.waitFor();
    }

    private static String elapsed()
    {
        long s = (System.nanoTime() - START) / 1_000_000_000L;
        return (s / 60) + "m" + (s % 60) + "s";
    }

    private static synchronized void log(String msg)
    {
        System.out.println("[" + elapsed() + "] " + msg);
        System.out.flush();
    }

    private static void die(String msg)
    {
        System.err.println("regress: " + msg);
        System.exit(2);
    }
}
