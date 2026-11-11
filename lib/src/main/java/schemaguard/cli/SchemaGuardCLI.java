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
        "2:Unexpected error (e.g. migration file not found).",
        "3:One or more SQL statements could not be parsed (analysis incomplete)."
    }
)
public class SchemaGuardCLI implements Callable<Integer> {

    static final int EXIT_OK            = 0;
    static final int EXIT_HIGH_RISK     = 1;
    static final int EXIT_ERROR         = 2;
    static final int EXIT_PARSE_FAILURE = 3;

    @Option(names = {"--source", "-s"}, description = "Spring Boot 소스 루트 (src/main/java)", required = true)
    private String sourcePath;

    @Option(names = {"--migration", "-m"}, description = "분석할 SQL 마이그레이션 파일", required = true)
    private String migrationPath;

    @Option(names = {"--output", "-o"}, description = "출력 포맷: console(기본값) | json", defaultValue = "console")
    private String outputFormat;

    @Option(names = {"--report-path", "-r"}, description = "JSON 저장 경로", defaultValue = "./schemaguard-report.json")
    private String reportPath;

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

            if (changes.isEmpty()) {
                System.out.println("No schema changes detected in migration file.");
                return finishWithParseCheck(parsed, EXIT_OK);
            }

            System.out.println("Detected " + changes.size() + " schema change(s):");
            changes.forEach(c -> System.out.println("  " + c));
            System.out.println();

            List<File> javaFiles = collectJavaFiles(new File(sourcePath));
            System.out.println("Scanning " + javaFiles.size() + " Java source files...");

            EntityParser entityParser = new EntityParser();
            RepositoryParser repositoryParser = new RepositoryParser();
            ServiceParser serviceParser = new ServiceParser();
            ControllerParser controllerParser = new ControllerParser();

            List<EntityMapping> entities = new ArrayList<>();
            List<FkMapping> fkMappings = new ArrayList<>();
            List<RepositoryInfo> repos = new ArrayList<>();
            List<ServiceInfo> services = new ArrayList<>();
            List<ControllerInfo> controllers = new ArrayList<>();

            for (File javaFile : javaFiles) {
                try {
                    EntityParser.ParseResult pr = entityParser.parse(javaFile);
                    entities.addAll(pr.entityMappings());
                    fkMappings.addAll(pr.fkMappings());

                    repos.addAll(repositoryParser.parse(javaFile));
                    services.addAll(serviceParser.parse(javaFile));
                    controllers.addAll(controllerParser.parse(javaFile));
                } catch (Exception e) {
                    System.err.println("  [WARN] Parse failed: " + javaFile.getName()
                            + " (" + e.getMessage() + ")");
                }
            }

            System.out.println("  Entities    : " + entities.size() + " fields");
            System.out.println("  FK Mappings : " + fkMappings.size() + " relations");
            System.out.println("  Repositories: " + repos.size());
            System.out.println("  Services    : " + services.size());
            System.out.println("  Controllers : " + controllers.size());
            System.out.println();

            DependencyGraphBuilder builder = new DependencyGraphBuilder();
            builder.setSourceRoot(sourcePath);   // ← 파일 위치를 상대경로로 정규화하기 위해 필요
            builder.build(entities, fkMappings, repos, services, controllers);

            ImpactAnalyzer analyzer = new ImpactAnalyzer(builder.getGraph(), builder.getNodeIndex());
            ImpactResult result = analyzer.analyze(changes);

            ReportGenerator reporter = switch (outputFormat.toLowerCase()) {
                case "json" -> new JsonReporter(reportPath);
                default -> new ConsoleReporter();
            };

            reporter.generate(result);

            return finishWithParseCheck(parsed, result.hasHighRisk() ? EXIT_HIGH_RISK : EXIT_OK);

        } catch (Exception e) {
            System.err.println("SchemaGuard error: " + e.getMessage());
            e.printStackTrace();
            return EXIT_ERROR;
        }
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

    private List<File> collectJavaFiles(File dir) {
        List<File> result = new ArrayList<>();

        if (!dir.exists() || !dir.isDirectory()) {
            return result;
        }

        File[] files = dir.listFiles();

        if (files == null) {
            return result;
        }

        for (File f : files) {
            if (f.isDirectory()) {
                result.addAll(collectJavaFiles(f));
            } else if (f.getName().endsWith(".java")) {
                result.add(f);
            }
        }

        return result;
    }
}