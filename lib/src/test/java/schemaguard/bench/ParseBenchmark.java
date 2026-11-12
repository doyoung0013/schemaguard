package schemaguard.bench;

import schemaguard.analyzer.ImpactAnalyzer;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 소스 파싱 구조 벤치마크: legacy(파서 4개가 각자 파싱) vs shared(파일당 1회 파싱, AST 공유).
 *
 * 실행: ./gradlew benchmark [-PbenchArgs="--sizes 100,1000,5000 --reps 3"]
 *
 * 각 (규모, 모드, 반복)은 별도 JVM에서 실행해 JIT 워밍업/GC 상태가 서로 섞이지 않게 한다.
 * 구간: collect(파일 수집) / parse(Java 파싱+추출) / graph(그래프 생성) / analyze(BFS)
 * 메모리
 *   - 파싱 단계 할당량: parse 구간에서 메인 스레드가 새로 할당한 바이트 (중복 파싱이 줄면 줄어든다)
 *   - 분석 후 유지 힙: 분석 종료 후 GC 직후 사용 중인 힙 (AST가 쌓여 남지 않는지 확인)
 *   - 최대 힙: 2ms 간격 샘플링 최대값. GC 정책(힙 크기)에 좌우되므로 참고용
 * 일치 여부: 영향 API·원인·경로와 추출 결과 전체를 정렬해 SHA-256으로 비교한다.
 */
public final class ParseBenchmark {

    private static final String MARK = "BENCH_RESULT ";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--worker")) {
            worker(args[1], Path.of(args[2]), Path.of(args[3]));
            return;
        }

        List<Integer> sizes = List.of(100, 1000, 5000);
        int reps = 3;
        String xmx = "2g";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sizes" -> sizes = Arrays.stream(args[++i].split(",")).map(Integer::parseInt).toList();
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--xmx" -> xmx = args[++i];
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        orchestrate(sizes, reps, xmx);
    }

    // ── 오케스트레이터 ───────────────────────────────────────────────────────

    private record Run(String mode, long files, long parseCalls, double collect, double parse,
                       double graph, double analyze, double total, double peakMb,
                       double allocMb, double retainedMb, String fingerprint) {}

    private static void orchestrate(List<Integer> sizes, int reps, String xmx) throws Exception {
        Path work = Path.of("build", "benchmark");
        Files.createDirectories(work);
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");

        StringBuilder md = new StringBuilder();
        md.append("| 파일 수 | 모드 | 파싱 호출 | collect | parse | graph | analyze | 전체 "
                + "| 파싱 단계 할당량 | 분석 후 유지 힙 | 최대 힙(샘플링) | 결과 일치 |\n");
        md.append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");

        for (int size : sizes) {
            Path project = work.resolve("sample-" + size);
            deleteRecursively(project);
            Path migration = SampleProjectGenerator.generate(project, size);
            Path src = project.resolve("src/main/java");
            System.out.println("== generated " + size + " files at " + project);

            Map<String, List<Run>> byMode = new LinkedHashMap<>();
            for (int r = 0; r < reps; r++) {
                // 모드 순서를 번갈아 실행해 디스크 캐시 등 순서 효과를 줄인다.
                List<String> order = r % 2 == 0 ? List.of("legacy", "shared") : List.of("shared", "legacy");
                for (String mode : order) {
                    Run run = spawn(javaBin, cp, xmx, mode, src, migration);
                    byMode.computeIfAbsent(mode, m -> new ArrayList<>()).add(run);
                    System.out.printf("   %-6s rep%d  parse=%8.1fms total=%8.1fms alloc=%8.1fMB retained=%6.1fMB peak=%6.1fMB calls=%d%n",
                            mode, r + 1, run.parse, run.total, run.allocMb, run.retainedMb, run.peakMb, run.parseCalls);
                }
            }

            Set<String> fps = new HashSet<>();
            byMode.values().forEach(list -> list.forEach(run -> fps.add(run.fingerprint)));
            String same = fps.size() == 1 ? "✅ 일치" : "❌ 불일치";

            for (Map.Entry<String, List<Run>> e : byMode.entrySet()) {
                List<Run> runs = e.getValue();
                Run first = runs.get(0);
                md.append(String.format("| %,d | %s | %,d | %s | %s | %s | %s | %s | %,.0f MB | %.1f MB | %.0f MB | %s |%n",
                        first.files, e.getKey(), first.parseCalls,
                        fmt(median(runs, Run::collect)), fmt(median(runs, Run::parse)),
                        fmt(median(runs, Run::graph)), fmt(median(runs, Run::analyze)),
                        fmt(median(runs, Run::total)), median(runs, Run::allocMb),
                        median(runs, Run::retainedMb), median(runs, Run::peakMb), same));
            }
            double legacyParse = median(byMode.get("legacy"), Run::parse);
            double sharedParse = median(byMode.get("shared"), Run::parse);
            double legacyTotal = median(byMode.get("legacy"), Run::total);
            double sharedTotal = median(byMode.get("shared"), Run::total);
            System.out.printf("   => parse %.2fx faster, total %.2fx faster, results %s%n",
                    legacyParse / sharedParse, legacyTotal / sharedTotal, same);
        }

        md.append(String.format("%n반복 %d회 중앙값, 각 실행은 별도 JVM(-Xmx%s), Java %s.%n",
                reps, xmx, System.getProperty("java.version")));
        Path out = Path.of("build", "reports", "benchmark", "parse-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println();
        System.out.println(md);
        System.out.println("saved: " + out.toAbsolutePath());
    }

    private static Run spawn(String javaBin, String cp, String xmx, String mode,
                             Path src, Path migration) throws Exception {
        Process p = new ProcessBuilder(javaBin, "-Xmx" + xmx, "-cp", cp,
                ParseBenchmark.class.getName(), "--worker", mode, src.toString(), migration.toString())
                .redirectErrorStream(true)
                .start();
        String result = null;
        StringBuilder log = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = br.readLine()) != null; ) {
                if (line.startsWith(MARK)) result = line.substring(MARK.length());
                else log.append(line).append('\n');
            }
        }
        if (p.waitFor() != 0 || result == null) {
            throw new IllegalStateException("worker failed (" + mode + "):\n" + log);
        }
        String[] f = result.split(" ");
        return new Run(mode, Long.parseLong(f[0]), Long.parseLong(f[1]),
                Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                Double.parseDouble(f[8]), Double.parseDouble(f[9]), f[10]);
    }

    // ── 워커 (별도 JVM) ──────────────────────────────────────────────────────

    private static void worker(String mode, Path src, Path migration) throws Exception {
        AtomicLong peak = new AtomicLong();
        Runtime rt = Runtime.getRuntime();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        System.gc();
        sampler.start();

        long t0 = System.nanoTime();
        List<SchemaChange> changes = new SqlMigrationParser().parse(migration.toFile()).changes();
        List<File> files = JavaSourceScanner.collectJavaFiles(src.toFile());
        long t1 = System.nanoTime();

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long allocBefore = threads.getCurrentThreadAllocatedBytes();
        JavaSourceScanner.Result scan = switch (mode) {
            case "legacy" -> legacyScan(files);
            case "shared" -> new JavaSourceScanner().scan(files);
            default -> throw new IllegalArgumentException(mode);
        };
        long t2 = System.nanoTime();
        long allocated = threads.getCurrentThreadAllocatedBytes() - allocBefore;

        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(src.toString());
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(), scan.services(), scan.controllers());
        long t3 = System.nanoTime();

        ImpactResult result = new ImpactAnalyzer(builder.getGraph(), builder.getNodeIndex(),
                builder.getFkFieldsReferencingTable()).analyze(changes);
        long t4 = System.nanoTime();

        sampler.interrupt();
        sampler.join();
        peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);

        // 분석 결과(scan/graph/result)는 아직 참조 중이다. 그 외(AST 등)가 남아 있지 않은지 본다.
        System.gc();
        System.gc();
        long retained = rt.totalMemory() - rt.freeMemory();

        String fp = fingerprint(scan, builder, result);
        System.out.println(MARK + String.join(" ",
                String.valueOf(files.size()), String.valueOf(scan.parseCalls()),
                ms(t1 - t0), ms(t2 - t1), ms(t3 - t2), ms(t4 - t3), ms(t4 - t0),
                mb(peak.get()), mb(allocated), mb(retained), fp));
    }

    /** 개선 전 SchemaGuardCLI의 스캔 루프를 그대로 옮긴 것. 파일마다 파서 4개가 각자 파싱한다. */
    private static JavaSourceScanner.Result legacyScan(List<File> javaFiles) {
        EntityParser entityParser = new EntityParser();
        RepositoryParser repositoryParser = new RepositoryParser();
        ServiceParser serviceParser = new ServiceParser();
        ControllerParser controllerParser = new ControllerParser();

        List<EntityMapping> entities = new ArrayList<>();
        List<FkMapping> fkMappings = new ArrayList<>();
        List<RepositoryInfo> repos = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<ControllerInfo> controllers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int calls = 0;

        for (File javaFile : javaFiles) {
            try {
                calls++;
                EntityParser.ParseResult pr = entityParser.parse(javaFile);
                entities.addAll(pr.entityMappings());
                fkMappings.addAll(pr.fkMappings());

                calls++;
                repos.addAll(repositoryParser.parse(javaFile));
                calls++;
                services.addAll(serviceParser.parse(javaFile));
                calls++;
                controllers.addAll(controllerParser.parse(javaFile));
            } catch (Exception e) {
                failed.add(javaFile.getPath());
            }
        }
        return new JavaSourceScanner.Result(entities, fkMappings, repos, services, controllers, failed, calls);
    }

    // ── 결과 지문 ────────────────────────────────────────────────────────────

    private static String fingerprint(JavaSourceScanner.Result scan, DependencyGraphBuilder builder,
                                      ImpactResult result) throws Exception {
        List<String> lines = new ArrayList<>();
        for (AffectedApi a : result.getAffectedApis()) {
            StringBuilder sb = new StringBuilder("api|" + a.getApiId() + "|" + a.getRiskLevel() + "|" + a.getCause());
            for (Node n : a.getImpactNodes()) {
                sb.append("|").append(n.getId()).append("@").append(n.getFilePath()).append(":").append(n.getLineNumber());
            }
            lines.add(sb.toString());
        }
        builder.getGraph().vertexSet().forEach(n -> lines.add("node|" + n.getId()));
        builder.getGraph().edgeSet().forEach(e -> lines.add("edge|"
                + builder.getGraph().getEdgeSource(e).getId() + "->"
                + builder.getGraph().getEdgeTarget(e).getId() + "|" + e.getRelationType()));
        lines.add("counts|" + scan.entities().size() + "|" + scan.fkMappings().size() + "|"
                + scan.repositories().size() + "|" + scan.services().size() + "|"
                + scan.controllers().size() + "|" + scan.failedFiles().size());
        Collections.sort(lines);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String l : lines) md.update((l + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest()).substring(0, 16) + ":" + result.getAffectedApis().size();
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static String fmt(double ms) {
        return ms >= 1000 ? String.format("%.2f s", ms / 1000) : String.format("%.0f ms", ms);
    }

    private static double median(List<Run> runs, java.util.function.ToDoubleFunction<Run> f) {
        double[] v = runs.stream().mapToDouble(f).sorted().toArray();
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
