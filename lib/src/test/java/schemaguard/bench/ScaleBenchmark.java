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

/**
 * 규모별 성능(확장성) 벤치마크: 파싱 이후 단계(그래프 생성, 영향 분석)가 규모에 따라 어떻게 늘어나는지 본다.
 *
 * 실행: ./gradlew scaleBenchmark [-PbenchArgs="--sizes 100,1000,5000,10000,20000 --changes 12,100,1000"]
 *
 * 규모(파일 수)마다 별도 JVM 하나에서:
 *   1) 샘플 프로젝트를 한 번 파싱한다 (파싱 비용은 ParseBenchmark 에서 따로 다룬다).
 *   2) 그래프 생성을 워밍업 1회 + 측정 3회 하고 중앙값을 낸다. 노드/엣지 수, 그래프 유지 힙도 기록한다.
 *   3) 변경 수(C)별 마이그레이션마다 영향 분석을 워밍업 1회 + 측정 3회 한다.
 *      변경은 테이블 t0..t(C-1)에 DROP COLUMN / RENAME TABLE / DROP TABLE / MODIFY 를 번갈아 건다.
 *      테이블 단위 변경은 "테이블의 모든 컬럼 노드"를 찾아야 하므로 nodeIndex 조회 비용이 그대로 드러난다.
 * 결과 지문(영향 API·원인·경로 SHA-256)으로 개선 전후 결과가 같은지 비교한다.
 */
public final class ScaleBenchmark {

    private static final String MARK = "SCALE_RESULT ";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--worker")) {
            worker(Path.of(args[1]), args[2]);
            return;
        }
        List<Integer> sizes = List.of(100, 1000, 5000, 10000, 20000);
        List<Integer> changes = List.of(12, 100, 1000);
        String xmx = "4g";
        String out = "build/reports/benchmark/scale-benchmark.md";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sizes" -> sizes = Arrays.stream(args[++i].split(",")).map(Integer::parseInt).toList();
                case "--changes" -> changes = Arrays.stream(args[++i].split(",")).map(Integer::parseInt).toList();
                case "--xmx" -> xmx = args[++i];
                case "--out" -> out = args[++i];
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        orchestrate(sizes, changes, xmx, Path.of(out));
    }

    private static void orchestrate(List<Integer> sizes, List<Integer> changes, String xmx, Path out)
            throws Exception {
        Path work = Path.of("build", "benchmark");
        Files.createDirectories(work);
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        String changeArg = String.join(",", changes.stream().map(String::valueOf).toList());

        StringBuilder md = new StringBuilder();
        md.append("| 파일 수 | 노드 | 엣지 | parse | graph | 그래프 유지 힙 |");
        changes.forEach(c -> md.append(" analyze (변경 ").append(c).append(") |"));
        md.append(" 결과 지문 |\n|---:|---:|---:|---:|---:|---:|");
        changes.forEach(c -> md.append("---:|"));
        md.append("---|\n");

        for (int size : sizes) {
            Path project = work.resolve("scale-" + size);
            if (!Files.isDirectory(project.resolve("src/main/java"))) {
                SampleProjectGenerator.generate(project, size);
            }
            System.out.println("== " + size + " files");
            Process p = new ProcessBuilder(javaBin, "-Xmx" + xmx, "-cp", cp,
                    ScaleBenchmark.class.getName(), "--worker", project.toString(), changeArg)
                    .redirectErrorStream(true).start();
            String result = null;
            StringBuilder log = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = br.readLine()) != null; ) {
                    if (line.startsWith(MARK)) result = line.substring(MARK.length());
                    else if (line.startsWith("  ")) System.out.println(line);
                    else log.append(line).append('\n');
                }
            }
            if (p.waitFor() != 0 || result == null) throw new IllegalStateException("worker failed:\n" + log);
            md.append(result).append('\n');
        }
        md.append(String.format("%n그래프/분석은 워밍업 1회 후 3회 중앙값, 규모마다 별도 JVM(-Xmx%s), Java %s.%n",
                xmx, System.getProperty("java.version")));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md.toString());
        System.out.println();
        System.out.println(md);
        System.out.println("saved: " + out.toAbsolutePath());
    }

    // ── 워커 ─────────────────────────────────────────────────────────────────

    private static void worker(Path project, String changeArg) throws Exception {
        Path src = project.resolve("src/main/java");
        List<File> files = JavaSourceScanner.collectJavaFiles(src.toFile());
        int groups = Math.max(1, files.size() / 4);

        long t0 = System.nanoTime();
        JavaSourceScanner.Result scan = new JavaSourceScanner().scan(files);
        double parseMs = (System.nanoTime() - t0) / 1e6;

        // 그래프 생성
        // 워밍업 1회 + 측정 3회 (만든 그래프는 버린다)
        List<Double> graphTimes = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            System.gc();
            long s = System.nanoTime();
            DependencyGraphBuilder b = new DependencyGraphBuilder();
            b.setSourceRoot(src.toString());
            b.build(scan.entities(), scan.fkMappings(), scan.repositories(), scan.services(), scan.controllers());
            long e = System.nanoTime();
            if (r > 0) graphTimes.add((e - s) / 1e6);
        }
        // 분석에 쓸 그래프: 만들기 전후 GC 직후 힙 차이 = 그래프가 차지하는 메모리
        Runtime rt = Runtime.getRuntime();
        System.gc(); System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(src.toString());
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(), scan.services(), scan.controllers());
        System.gc(); System.gc();
        long after = rt.totalMemory() - rt.freeMemory();
        double graphMb = Math.max(0, after - before) / 1024.0 / 1024.0;

        int nodes = builder.getGraph().vertexSet().size();
        int edges = builder.getGraph().edgeSet().size();
        System.out.printf("  files=%d nodes=%,d edges=%,d parse=%.0fms graph=%s%n",
                files.size(), nodes, edges, parseMs, graphTimes);

        StringBuilder row = new StringBuilder(String.format("| %,d | %,d | %,d | %s | %s | %.0f MB |",
                files.size(), nodes, edges, fmt(parseMs), fmt(median(graphTimes)), graphMb));
        List<String> fps = new ArrayList<>();

        for (String c : changeArg.split(",")) {
            int count = Integer.parseInt(c);
            List<SchemaChange> changes = new SqlMigrationParser().parseSQL(migration(count, groups)).changes();
            ImpactAnalyzer analyzer = new ImpactAnalyzer(builder.getGraph(), builder.getNodeIndex(),
                    builder.getFkFieldsReferencingTable());
            List<Double> times = new ArrayList<>();
            ImpactResult result = null;
            for (int r = 0; r < 4; r++) {
                long s = System.nanoTime();
                result = analyzer.analyze(changes);
                long e = System.nanoTime();
                if (r > 0) times.add((e - s) / 1e6);
            }
            System.out.printf("  changes=%d (parsed %d) affected=%d analyze=%s%n",
                    count, changes.size(), result.getAffectedApis().size(), times);
            row.append(' ').append(fmt(median(times))).append(" |");
            fps.add(fingerprint(result));
        }
        row.append(' ').append(String.join(" ", fps)).append(" |");
        System.out.println(MARK + row);
    }

    /** 테이블 t0..t(count-1)에 네 종류 변경을 번갈아 건다 (테이블 수보다 많으면 순환). */
    static String migration(int count, int groups) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int t = i % groups;
            switch (i % 4) {
                case 0 -> sql.append("ALTER TABLE t").append(t).append(" DROP COLUMN email;\n");
                case 1 -> sql.append("ALTER TABLE t").append(t).append(" RENAME TO t").append(t).append("_new;\n");
                case 2 -> sql.append("DROP TABLE t").append(t).append(";\n");
                default -> sql.append("ALTER TABLE t").append(t).append(" MODIFY name VARCHAR(10);\n");
            }
        }
        return sql.toString();
    }

    private static String fingerprint(ImpactResult result) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (AffectedApi a : result.getAffectedApis()) {
            StringBuilder sb = new StringBuilder(a.getApiId() + "|" + a.getRiskLevel() + "|" + a.getCause());
            for (Node n : a.getImpactNodes()) sb.append('|').append(n.getId());
            md.update((sb + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 8) + ":" + result.getAffectedApis().size();
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static String fmt(double ms) {
        return ms >= 1000 ? String.format("%.2f s", ms / 1000) : String.format("%.1f ms", ms);
    }
}
