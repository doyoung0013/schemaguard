package schemaguard.cli;

import schemaguard.analyzer.ImpactAnalyzer;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;
import schemaguard.report.ConsoleReporter;
import schemaguard.report.JsonReporter;
import schemaguard.report.ReportGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name        = "schemaguard",
    description = "Detects APIs affected by DB schema changes in Spring Boot + JPA projects.",
    mixinStandardHelpOptions = true,
    version     = "SchemaGuard 1.0",
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = {
        "0:No HIGH risk detected and every SQL statement was parsed.",
        "1:HIGH risk change affects at least one API.",
        "2:Unexpected error (e.g. migration file not found, report write failed).",
        "3:One or more SQL statements could not be parsed (analysis incomplete)."
    }
)
public class SchemaGuardCLI implements Callable<Integer> {

    static final int EXIT_OK            = 0;
    static final int EXIT_HIGH_RISK     = 1;
    static final int EXIT_ERROR         = 2;
    static final int EXIT_PARSE_FAILURE = 3;

    private static final String DEFAULT_REPORT_PATH = "./schemaguard-report.json";

    @Option(names = {"--source", "-s"}, description = "Spring Boot 소스 루트 (src/main/java)", required = true)
    private String sourcePath;

    @Option(names = {"--migration", "-m"}, description = "분석할 SQL 마이그레이션 파일", required = true)
    private String migrationPath;

    @Option(names = {"--output", "-o"},
            description = "출력 포맷: console(기본값, --report-path 지정 시 JSON도 저장) | json(JSON만)",
            defaultValue = "console")
    private String outputFormat;

    @Option(names = {"--report-path", "-r"},
            description = "JSON 리포트 저장 경로. 지정하면 console 모드에서도 JSON을 함께 저장한다. "
                    + "(json 모드 기본값: " + DEFAULT_REPORT_PATH + ")")
    private String reportPath;

    @Option(names = "--timing", description = "구간별 소요 시간(파일 수집/파싱/그래프/분석) 출력")
    private boolean timing;

    @Override
    public Integer call() {
        try {
            System.out.println("SchemaGuard: Starting analysis...");
            System.out.println("  Source    : " + sourcePath);
            System.out.println("  Migration : " + migrationPath);
            System.out.println();

            MigrationParser migrationParser = new MigrationParser();
            MigrationParser.ParseResult parsed = migrationParser.parse(new File(migrationPath));
            List<SchemaChange> changes = parsed.changes();

            System.out.printf("Parsed %d SQL statement(s): %d ok, %d failed%n",
                    parsed.totalStatements(),
                    parsed.totalStatements() - parsed.failures().size(),
                    parsed.failures().size());
            printParseFailures(parsed);
            System.out.println();

            List<String> sourceParseFailures = new ArrayList<>();
            ImpactResult result;

            if (changes.isEmpty()) {
                System.out.println("No schema changes detected in migration file.");
                result = new ImpactResult(List.of());
            } else {
                System.out.println("Detected " + changes.size() + " schema change(s):");
                changes.forEach(c -> System.out.println("  " + c));
                System.out.println();
                result = analyze(changes, sourceParseFailures);
            }

            // 변경이 없어도 리포트는 항상 만든다. CI가 리포트 파일 유무로 헷갈리지 않도록.
            for (ReportGenerator reporter : reporters(parsed, sourceParseFailures)) {
                reporter.generate(result);
            }

            return finishWithParseCheck(parsed, result.hasHighRisk() ? EXIT_HIGH_RISK : EXIT_OK);

        } catch (Exception e) {
            System.err.println("SchemaGuard error: " + e.getMessage());
            e.printStackTrace();
            return EXIT_ERROR;
        }
    }

    private ImpactResult analyze(List<SchemaChange> changes, List<String> sourceParseFailures) {
        long t0 = System.nanoTime();
        List<File> javaFiles = SourceScanner.collectJavaFiles(new File(sourcePath));
        long t1 = System.nanoTime();
        System.out.println("Scanning " + javaFiles.size() + " Java source files...");

        // 파일당 한 번 파싱한 AST를 네 추출기가 공유한다.
        SourceScanner.Result scan = new SourceScanner().scan(javaFiles);
        sourceParseFailures.addAll(scan.failedFiles());
        long t2 = System.nanoTime();

        System.out.println("  Entities    : " + scan.entities().size() + " fields");
        System.out.println("  FK Mappings : " + scan.fkMappings().size() + " relations");
        System.out.println("  Repositories: " + scan.repositories().size());
        System.out.println("  Services    : " + scan.services().size());
        System.out.println("  Controllers : " + scan.controllers().size());
        System.out.println();

        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(sourcePath);   // ← 파일 위치를 상대경로로 정규화하기 위해 필요
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(),
                scan.services(), scan.controllers());
        long t3 = System.nanoTime();

        ImpactAnalyzer analyzer = new ImpactAnalyzer(builder.getGraph(), builder.getNodeIndex(),
                builder.getFkFieldsReferencingTable());
        ImpactResult result = analyzer.analyze(changes);
        long t4 = System.nanoTime();

        if (timing) {
            System.out.printf("Timing (ms): collect %.1f | parse %.1f (%d parse calls) | graph %.1f | analyze %.1f%n%n",
                    ms(t1 - t0), ms(t2 - t1), scan.parseCalls(), ms(t3 - t2), ms(t4 - t3));
        }
        return result;
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * console(기본): 콘솔 리포트 + (--report-path 지정 시) JSON 리포트
     * json        : JSON 리포트만 (경로 미지정 시 기본 경로)
     */
    private List<ReportGenerator> reporters(MigrationParser.ParseResult parsed,
                                            List<String> sourceParseFailures) {
        List<ReportGenerator> list = new ArrayList<>();
        boolean jsonOnly = "json".equalsIgnoreCase(outputFormat);

        if (!jsonOnly) {
            list.add(new ConsoleReporter());
        }
        if (jsonOnly || reportPath != null) {
            String path = reportPath != null ? reportPath : DEFAULT_REPORT_PATH;
            list.add(new JsonReporter(path, parsed, sourceParseFailures));
        }
        return list;
    }

    /**
     * 파싱 실패 구문이 있으면 분석 결과와 무관하게 차단한다.
     * 검사 도구가 읽지 못한 변경은 "변경 없음"이 아니라 "알 수 없음"이기 때문이다.
     */
    private int finishWithParseCheck(MigrationParser.ParseResult parsed, int exitCode) {
        if (!parsed.hasFailures()) {
            return exitCode;
        }
        System.err.println();
        System.err.printf("[BLOCKED] %d of %d SQL statement(s) could not be parsed; "
                        + "the analysis above is incomplete. Exit code %d.%n",
                parsed.failures().size(), parsed.totalStatements(), EXIT_PARSE_FAILURE);
        return EXIT_PARSE_FAILURE;
    }

    private void printParseFailures(MigrationParser.ParseResult parsed) {
        for (MigrationParser.ParseFailure f : parsed.failures()) {
            String stmt = f.statement().replaceAll("\\s+", " ");
            if (stmt.length() > 120) stmt = stmt.substring(0, 117) + "...";
            System.err.printf("  [WARN] Unparsed statement at %s:%d%n", migrationPath, f.line());
            System.err.println("         SQL   : " + stmt);
            System.err.println("         Reason: " + f.reason());
        }
    }

}
