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
    version     = "SchemaGuard 1.0"
)
public class SchemaGuardCLI implements Callable<Integer> {

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
            List<SchemaChange> changes = migrationParser.parse(new File(migrationPath));

            if (changes.isEmpty()) {
                System.out.println("No schema changes detected in migration file.");
                return 0;
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

            if (result.hasHighRisk()) {
                return 1;
            }

            return 0;

        } catch (Exception e) {
            System.err.println("SchemaGuard error: " + e.getMessage());
            e.printStackTrace();
            return 2;
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