package schemaguard.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import schemaguard.analyzer.ChangeClassifier;
import schemaguard.analyzer.ImpactAnalyzer;
import schemaguard.bench.SampleProjectGenerator;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.ImpactResult;
import schemaguard.model.SchemaChange;
import schemaguard.report.JsonReporter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 병렬 파싱(--threads N)이 순차 파싱과 같은 결과를 내는지 확인한다.
 *
 * 개수나 정렬한 지문만 비교하지 않고 "순서까지" 비교한다.
 * 그래프 노드·엣지의 삽입 순서, 파싱 실패 목록 순서, 타입 해석 통계, JSON 리포트 바이트가 모두 같아야 한다.
 * 파일별 결과를 입력 순서대로 합치지 않으면 노드·엣지 순서가 달라져 이 테스트가 깨진다.
 */
class ParallelScanTest {

    @TempDir
    Path tmp;

    @Test
    void parallelScanMatchesSequentialIncludingOrder() throws Exception {
        Path migration = SampleProjectGenerator.generate(tmp, 400);
        Path src = tmp.resolve("src/main/java");
        // 파싱 실패 파일을 여러 위치에 섞는다. 실패 목록과 경고 순서도 입력 순서여야 한다.
        for (int k : new int[]{0, 17, 55, 98}) {
            Path dir = src.resolve("com/example/g0/m" + k);
            Files.writeString(dir.resolve("Broken" + k + ".java"), "public class Broken" + k + " { void x( }");
        }
        List<File> files = JavaSourceScanner.collectJavaFiles(src.toFile());
        assertEquals(404, files.size());

        Snapshot sequential = run(files, migration, null, 1);
        assertEquals(4, sequential.failed.size());
        assertFalse(sequential.report.contains("\"affectedApis\" : [ ]"), "sample must have affected APIs");

        for (int threads : new int[]{2, 3, 4, 8, 16}) {
            Snapshot parallel = run(files, migration, null, threads);
            sequential.assertSame(parallel, "threads=" + threads);
        }
    }

    @Test
    void parallelScanWithSymbolSolverMatchesSequential() throws Exception {
        Path migration = SampleProjectGenerator.generate(tmp, 120);
        Path src = tmp.resolve("src/main/java");
        List<File> files = JavaSourceScanner.collectJavaFiles(src.toFile());

        // symbol solver 는 스레드마다 따로 만든다(캐시를 공유하지 않는다).
        Snapshot sequential = run(files, migration, src.toFile(), 1);
        Snapshot parallel = run(files, migration, src.toFile(), 4);
        sequential.assertSame(parallel, "symbol solver, threads=4");
    }

    @Test
    void moreThreadsThanFilesAndEmptyInput() {
        JavaSourceScanner scanner = new JavaSourceScanner();
        JavaSourceScanner.Result empty = scanner.scan(List.of(), null, 8);
        assertEquals(0, empty.parseCalls());
        assertTrue(empty.entities().isEmpty());
        assertArrayEquals(new int[4], scanner.typeResolutionCounts());
    }

    private record Snapshot(List<String> nodes, List<String> edges, List<String> failed,
                            String counts, int[] typeResolution, String report) {
        void assertSame(Snapshot other, String label) {
            assertEquals(nodes, other.nodes, label + ": graph node order");
            assertEquals(edges, other.edges, label + ": graph edge order");
            assertEquals(failed, other.failed, label + ": failed file order");
            assertEquals(counts, other.counts, label + ": extracted counts");
            assertArrayEquals(typeResolution, other.typeResolution, label + ": type resolution stats");
            assertEquals(report, other.report, label + ": JSON report bytes");
        }
    }

    private Snapshot run(List<File> files, Path migration, File sourceRoot, int threads) throws Exception {
        JavaSourceScanner scanner = new JavaSourceScanner();
        JavaSourceScanner.Result scan = scanner.scan(files, sourceRoot, threads);
        assertEquals(files.size(), scan.parseCalls());

        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(tmp.resolve("src/main/java").toString());
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(), scan.services(), scan.controllers());

        List<String> nodes = new ArrayList<>();
        builder.getGraph().vertexSet().forEach(n -> nodes.add(n.getId() + "@" + n.getFilePath() + ":" + n.getLineNumber()));
        List<String> edges = new ArrayList<>();
        builder.getGraph().edgeSet().forEach(e -> edges.add(builder.getGraph().getEdgeSource(e).getId() + "->"
                + builder.getGraph().getEdgeTarget(e).getId() + "|" + e.getRelationType()));

        SqlMigrationParser.ParseResult parsed = new SqlMigrationParser().parse(migration.toFile());
        List<SchemaChange> classified = new ChangeClassifier()
                .classify(parsed.changes(), scan.entities(), scan.fkMappings(), scan.entityIndexes());
        ImpactResult result = ImpactAnalyzer.of(builder).analyze(classified);
        Path out = tmp.resolve("report-" + threads + "-" + (sourceRoot != null) + ".json");
        new JsonReporter(out.toString(),
                new SqlMigrationParser.ParseResult(classified, parsed.failures(), parsed.totalStatements()),
                scan.failedFiles()).generate(result);

        String counts = Arrays.toString(new int[]{scan.entities().size(), scan.fkMappings().size(),
                scan.entityIndexes().size(), scan.repositories().size(), scan.services().size(),
                scan.controllers().size()});
        return new Snapshot(nodes, edges, scan.failedFiles(), counts, scanner.typeResolutionCounts(),
                Files.readString(out, StandardCharsets.UTF_8));
    }
}
