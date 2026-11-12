package schemaguard.cli;

import schemaguard.analyzer.ChangeClassifier;
import schemaguard.analyzer.ImpactAnalyzer;
import schemaguard.analyzer.LockScopeAnalyzer;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;
import schemaguard.report.ConsoleReporter;
import schemaguard.report.JsonReporter;
import schemaguard.report.ReportGenerator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Path;
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

    @Option(names = {"--migration", "-m"}, arity = "1..*", required = true,
            description = "분석할 SQL 마이그레이션 파일 또는 폴더. 여러 개를 주면(-m a.sql b.sql, 또는 -m 를 반복) "
                    + "Java 소스는 한 번만 파싱하고 각 마이그레이션을 따로 분석한다. 폴더는 그 안의 .sql 을 파일 이름의 V 번호 순서로 분석한다.")
    private List<String> migrationPaths;

    @Option(names = {"--output", "-o"},
            description = "출력 포맷: console(기본값, --report-path 지정 시 JSON도 저장) | json(JSON만)",
            defaultValue = "console")
    private String outputFormat;

    @Option(names = {"--report-path", "-r"},
            description = "JSON 리포트 저장 경로. 지정하면 console 모드에서도 JSON을 함께 저장한다. "
                    + "(json 모드 기본값: " + DEFAULT_REPORT_PATH + ") 마이그레이션이 하나일 때만 쓴다.")
    private String reportPath;

    @Option(names = "--report-dir",
            description = "JSON 리포트를 <폴더>/<마이그레이션 파일명>.json 으로 저장하고, 파일별 종료 코드를 "
                    + "<폴더>/" + RESULTS_FILE + " 에 남긴다. 마이그레이션이 여러 개일 때 쓴다. "
                    + "(여러 개 + json 모드 기본값: " + DEFAULT_REPORT_DIR + ")")
    private String reportDir;

    @Option(names = "--history", paramLabel = "<폴더>",
            description = "이전 마이그레이션 파일이 있는 폴더. 주면 검사 파일보다 파일 이름의 V 번호가 작은 파일"
                    + "(V<번호>__<설명>.sql)을 번호 순서로 읽어 DROP INDEX 의 대상(컬럼, UNIQUE 여부)을 찾는다. "
                    + "주지 않으면(기본) 이전 파일은 읽지 않고, 검사 파일 안의 앞선 구문과 엔티티 선언만 본다.")
    private String historyDir;

    @Option(names = "--threads", defaultValue = "0",
            description = "Java 파싱 스레드 수. 0(기본)이면 사용 가능한 코어 수의 절반(1~4), 1이면 순차 파싱.")
    private int threads;

    @Option(names = "--lock-scope", negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "인덱스 삭제가 비관적 락 조회(@Lock, 네이티브 FOR UPDATE)의 잠금 범위를 넓히는지 본다 (기본: 켬). "
                    + "--no-lock-scope 로 끄면 LOCK_SCOPE_WIDENED 경고를 만들지 않는다.")
    private boolean lockScope;

    @Option(names = "--timing", description = "구간별 소요 시간(파일 수집/파싱/그래프/분석) 출력")
    private boolean timing;

    @Option(names = "--symbol-solver",
            description = "선언·추론으로 타입을 모르는 getter 체인 루트를 JavaParser symbol solver 로 한 번 더 해석한다. "
                    + "Lombok·Spring 이 클래스패스에 없어 실패하는 경우가 많고 파싱이 느려져 기본은 끈다.")
    private boolean symbolSolver;

    static final String DEFAULT_REPORT_DIR = "./schemaguard-reports";
    static final String RESULTS_FILE = "results.tsv";

    /** 파싱한 Java 소스와 그래프. 처음 필요할 때 한 번 만들고, 이후 마이그레이션은 다시 쓴다. */
    private record SourceModel(JavaSourceScanner.Result scan, DependencyGraphBuilder builder,
                               List<String> parseFailures, int[] typeResolution, int threads,
                               long collectNanos, long parseNanos, long graphNanos) {}

    private SourceModel source;

    @Override
    public Integer call() {
        // --report-dir 는 파일 수와 관계없이 results.tsv 를 남겨야 하므로(CI 가 파일 하나만 바꿔도) 여러 개 경로를 탄다.
        boolean single = migrationPaths.size() == 1 && !new File(migrationPaths.get(0)).isDirectory()
                && reportDir == null;
        if (single) {
            return runSingle(migrationPaths.get(0));
        }
        return runMany();
    }

    /** 마이그레이션 하나: 출력과 리포트는 여러 개를 받기 전과 같다. */
    private int runSingle(String migrationPath) {
        try {
            System.out.println("SchemaGuard: Starting analysis...");
            System.out.println("  Source    : " + sourcePath);
            System.out.println("  Migration : " + migrationPath);
            System.out.println();
            if (!historyDirIsValid()) return EXIT_ERROR;

            String json = reportPath;
            if (json == null && jsonOnly()) json = DEFAULT_REPORT_PATH;

            return analyzeMigration(new File(migrationPath), migrationPath, json);

        } catch (Exception e) {
            System.err.println("SchemaGuard error: " + e.getMessage());
            e.printStackTrace();
            return EXIT_ERROR;
        }
    }

    /**
     * 마이그레이션 여러 개: Java 소스 파싱과 그래프 생성은 한 번만 하고, 마이그레이션마다 따로 분석·리포트한다.
     * --history 를 주면 각 마이그레이션의 이력(DROP INDEX 용 인덱스 정의)은 따로 실행할 때처럼
     * 그 폴더에서 그 파일보다 V 번호가 작은 파일로 읽는다. 함께 분석하는 파일끼리는 영향을 주지 않는다.
     * 그래서 파일별 리포트는 하나씩 실행한 리포트와 같다.
     * 한 파일이 실패해도 나머지를 끝까지 분석한다. 전체 종료 코드는 가장 심각한 것이다 (2 > 3 > 1 > 0).
     */
    private int runMany() {
        System.out.println("SchemaGuard: Starting analysis...");
        System.out.println("  Source    : " + sourcePath);

        List<File> migrations;
        try {
            migrations = resolveMigrations();
        } catch (IllegalArgumentException e) {
            System.err.println("SchemaGuard error: " + e.getMessage());
            return EXIT_ERROR;
        }
        System.out.println("  Migrations: " + migrations.size() + " file(s)");
        migrations.forEach(m -> System.out.println("    " + m.getPath()));
        System.out.println();
        if (!historyDirIsValid()) return EXIT_ERROR;

        if (reportPath != null) {
            System.err.println(reportDir != null
                    ? "SchemaGuard error: use either --report-path or --report-dir, not both"
                    : "SchemaGuard error: --report-path takes a single migration; use --report-dir for several migrations");
            return EXIT_ERROR;
        }
        if (migrations.isEmpty()) {
            System.err.println("SchemaGuard error: no .sql migration files found in " + migrationPaths);
            return EXIT_ERROR;
        }
        String dir = reportDir != null ? reportDir : (jsonOnly() ? DEFAULT_REPORT_DIR : null);
        if (dir != null) {
            String clash = duplicateReportName(migrations);
            if (clash != null) {
                System.err.println("SchemaGuard error: two migrations would write the same report " + clash
                        + "; run them separately");
                return EXIT_ERROR;
            }
        }

        List<Integer> codes = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        int overall = EXIT_OK;
        for (int i = 0; i < migrations.size(); i++) {
            File m = migrations.get(i);
            String json = dir != null ? reportFile(m, dir).getPath() : null;
            System.out.printf("==== [%d/%d] %s ====%n", i + 1, migrations.size(), m.getPath());
            int code;
            try {
                code = analyzeMigration(m, m.getPath(), json);
            } catch (Exception e) {
                System.err.println("SchemaGuard error (" + m.getPath() + "): " + e.getMessage());
                e.printStackTrace();
                code = EXIT_ERROR;
            }
            System.out.println();
            codes.add(code);
            reports.add(json);
            overall = worse(overall, code);
        }

        System.out.println("==== Summary ====");
        for (int i = 0; i < migrations.size(); i++) {
            System.out.printf("  exit %d  %s%n", codes.get(i), migrations.get(i).getPath());
        }
        if (timing && source != null) {
            System.out.printf("Source parsed once for %d migration(s): collect %.1f | parse %.1f | graph %.1f (ms)%n",
                    migrations.size(), ms(source.collectNanos()), ms(source.parseNanos()), ms(source.graphNanos()));
        }
        System.out.println("Overall exit code: " + overall);

        if (dir != null) {
            StringBuilder tsv = new StringBuilder();
            for (int i = 0; i < migrations.size(); i++) {
                tsv.append(migrations.get(i).getPath()).append('\t').append(codes.get(i))
                        .append('\t').append(reports.get(i)).append('\n');
            }
            try {
                File out = new File(dir, RESULTS_FILE);
                java.nio.file.Files.createDirectories(out.getAbsoluteFile().getParentFile().toPath());
                java.nio.file.Files.writeString(out.toPath(), tsv.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                System.err.println("SchemaGuard error: cannot write " + RESULTS_FILE + ": " + e.getMessage());
                return EXIT_ERROR;
            }
        }
        return overall;
    }

    private boolean historyDirIsValid() {
        if (historyDir == null || new File(historyDir).isDirectory()) return true;
        System.err.println("SchemaGuard error: --history folder not found: " + historyDir);
        return false;
    }

    /** 종료 코드의 심각도: 실행 오류(2) > 분석 불완전(3) > HIGH(1) > 통과(0) */
    static int worse(int a, int b) {
        return severity(a) >= severity(b) ? a : b;
    }

    private static int severity(int code) {
        return switch (code) {
            case EXIT_OK -> 0;
            case EXIT_HIGH_RISK -> 1;
            case EXIT_PARSE_FAILURE -> 2;
            default -> 3;
        };
    }

    /** 인자로 받은 파일·폴더를 분석 순서의 파일 목록으로 펼친다. 같은 파일은 한 번만. */
    private List<File> resolveMigrations() {
        java.util.LinkedHashMap<String, File> seen = new java.util.LinkedHashMap<>();
        List<File> explicit = new ArrayList<>();
        for (String p : migrationPaths) {
            File f = new File(p);
            List<File> files = f.isDirectory() ? MigrationHistory.sqlFilesIn(f) : List.of(f);
            for (File x : files) {
                String key = x.getAbsoluteFile().toPath().normalize().toString();
                if (seen.putIfAbsent(key, x) == null) explicit.add(x);
            }
        }
        // 인자 순서와 상관없이 파일 이름의 V 번호 순서로 분석한다. 리포트와 요약 순서가 매번 같다.
        return MigrationHistory.inApplyOrder(explicit);
    }

    private String duplicateReportName(List<File> migrations) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (File m : migrations) {
            String n = reportFile(m, ".").getName();
            if (!names.add(n)) return n;
        }
        return null;
    }

    private static File reportFile(File migration, String dir) {
        String name = migration.getName();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".sql")) name = name.substring(0, name.length() - 4);
        return new File(dir, name + ".json");
    }

    private boolean jsonOnly() {
        return "json".equalsIgnoreCase(outputFormat);
    }

    /** 마이그레이션 하나를 분석하고 리포트를 만든다. jsonPath 가 null 이면 JSON 리포트를 만들지 않는다. */
    private int analyzeMigration(File migration, String displayPath, String jsonPath) throws Exception {
        // DROP INDEX 에는 이름만 있다. --history 를 주면 이전 마이그레이션에서 인덱스 정의(컬럼, UNIQUE 여부)를 알아 둔다.
        // 주지 않으면 이전 파일은 읽지 않는다. 검사 파일 안의 앞선 구문과 엔티티 선언으로만 찾는다.
        List<File> history = historyDir != null
                ? MigrationHistory.earlierThan(migration, new File(historyDir)) : List.of();
        IndexCatalog catalog = IndexCatalog.load(history);
        if (historyDir != null && !MigrationHistory.hasVersion(migration)) {
            System.out.println("Migration history: not used (file name has no V number: " + migration.getName() + ")");
        } else if (historyDir != null) {
            System.out.printf("Migration history: %d earlier file(s), %d index/constraint definition(s)%s%n",
                    catalog.files(), catalog.size(),
                    catalog.failedStatements() > 0
                            ? ", " + catalog.failedStatements() + " statement(s) skipped (unparseable)" : "");
        }

        SqlMigrationParser migrationParser = new SqlMigrationParser(catalog).historyNotRead(historyDir == null);
        SqlMigrationParser.ParseResult parsed = migrationParser.parse(migration);
        List<SchemaChange> changes = parsed.changes();

        System.out.printf("Parsed %d SQL statement(s): %d ok, %d failed%n",
                parsed.totalStatements(),
                parsed.totalStatements() - parsed.failures().size(),
                parsed.failures().size());
        printParseFailures(parsed, displayPath);
        System.out.println();

        List<String> sourceParseFailures = new ArrayList<>();
        ImpactResult result;
        SqlMigrationParser.ParseResult reported = parsed;

        if (changes.isEmpty()) {
            System.out.println("No schema changes detected in migration file.");
            result = new ImpactResult(List.of());
        } else {
            System.out.println("Detected " + changes.size() + " schema change(s):");
            changes.forEach(c -> System.out.println("  " + c));
            System.out.println();
            List<SchemaChange> classified = new ArrayList<>();
            result = analyze(changes, sourceParseFailures, classified, migrationParser.catalogAfterParse());
            // 리포트의 detectedChanges 는 엔티티 매핑과 비교해 재분류한 결과를 보여준다.
            reported = new SqlMigrationParser.ParseResult(classified, parsed.failures(), parsed.totalStatements());
        }

        // 변경이 없어도 리포트는 항상 만든다. CI가 리포트 파일 유무로 헷갈리지 않도록.
        for (ReportGenerator reporter : reporters(reported, sourceParseFailures, jsonPath)) {
            reporter.generate(result);
        }

        return finishWithParseCheck(parsed, result.hasHighRisk() ? EXIT_HIGH_RISK : EXIT_OK);
    }

    private ImpactResult analyze(List<SchemaChange> changes, List<String> sourceParseFailures,
                                 List<SchemaChange> classifiedOut, IndexCatalog afterMigration) {
        boolean loadedNow = source == null;
        SourceModel src = source();
        JavaSourceScanner.Result scan = src.scan();
        sourceParseFailures.addAll(src.parseFailures());
        LockScopeAnalyzer locks = lockScope ? new LockScopeAnalyzer(scan.repositories(), scan.entities(),
                scan.fkMappings(), scan.entityIndexes(), afterMigration, historyDir != null) : null;

        System.out.println("  Entities    : " + scan.entities().size() + " fields");
        System.out.println("  FK Mappings : " + scan.fkMappings().size() + " relations");
        System.out.println("  Repositories: " + scan.repositories().size());
        System.out.println("  Services    : " + scan.services().size());
        System.out.println("  Controllers : " + scan.controllers().size());
        if (locks != null && locks.lockMethodCount() > 0)
            System.out.println("  Lock methods: " + locks.lockMethodCount() + " (@Lock PESSIMISTIC_*)");
        System.out.println();

        long t3 = System.nanoTime();
        // SQL 만 보고 정한 분류를 코드(엔티티 매핑)가 기대하는 스키마와 비교해 다시 정한다.
        List<SchemaChange> classified = new ChangeClassifier().historyNotRead(historyDir == null)
                .classify(changes, scan.entities(), scan.fkMappings(), scan.entityIndexes());
        // 인덱스 삭제가 비관적 락 조회의 잠금 범위를 넓히면 LOCK_SCOPE_WIDENED (MEDIUM) 경고를 덧붙인다.
        if (locks != null) classified = locks.analyze(classified);
        classifiedOut.addAll(classified);
        printReclassified(changes, classified);

        ImpactResult result = ImpactAnalyzer.of(src.builder()).analyze(classified);
        long t4 = System.nanoTime();

        if (timing) {
            if (loadedNow) {
                System.out.printf("Timing (ms): collect %.1f | parse %.1f (%d parse calls, %d thread(s)) | graph %.1f | analyze %.1f%n",
                        ms(src.collectNanos()), ms(src.parseNanos()), scan.parseCalls(), src.threads(),
                        ms(src.graphNanos()), ms(t4 - t3));
            } else {
                System.out.printf("Timing (ms): analyze %.1f (source parsed earlier, reused)%n", ms(t4 - t3));
            }
            int[] tr = src.typeResolution();
            System.out.printf("Association chain roots: declared %d | inferred %d | symbol solver %d | unresolved %d; links %s%n%n",
                    tr[0], tr[1], tr[2], tr[3], src.builder().getAssociationResolution());
        }
        return result;
    }

    /** Java 소스를 한 번 파싱하고 그래프를 만든다. 이미 만들었으면 그대로 돌려준다. */
    private SourceModel source() {
        if (source != null) {
            System.out.println("Reusing " + source.scan().parseCalls() + " Java source files parsed earlier");
            return source;
        }
        long t0 = System.nanoTime();
        List<File> javaFiles = JavaSourceScanner.collectJavaFiles(new File(sourcePath));
        long t1 = System.nanoTime();
        int n = threads > 0 ? threads : JavaSourceScanner.defaultThreads();
        System.out.println("Scanning " + javaFiles.size() + " Java source files...");

        // 파일당 한 번 파싱한 AST를 네 추출기가 공유한다. 스레드마다 파서와 추출기를 따로 둔다.
        JavaSourceScanner scanner = new JavaSourceScanner();
        JavaSourceScanner.Result scan = scanner.scan(javaFiles, symbolSolver ? new File(sourcePath) : null, n);
        long t2 = System.nanoTime();

        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(sourcePath);   // ← 파일 위치를 상대경로로 정규화하기 위해 필요
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(),
                scan.services(), scan.controllers());
        long t3 = System.nanoTime();

        source = new SourceModel(scan, builder, relativeToSource(scan.failedFiles()),
                scanner.typeResolutionCounts(), Math.max(1, Math.min(n, javaFiles.size())),
                t1 - t0, t2 - t1, t3 - t2);
        return source;
    }

    /** 리포트에 남는 경로를 --source 기준 상대경로 + '/' 구분자로 통일한다 (OS 간 같은 리포트). */
    private List<String> relativeToSource(List<String> paths) {
        Path root = Path.of(sourcePath).toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            Path abs = Path.of(p).toAbsolutePath().normalize();
            Path rel = abs.startsWith(root) ? root.relativize(abs) : abs;
            out.add(rel.toString().replace('\\', '/'));
        }
        out.sort(null);
        return out;
    }

    private static void printReclassified(List<SchemaChange> before, List<SchemaChange> after) {
        List<SchemaChange> changed = after.stream().filter(c -> c.getNote() != null).toList();
        if (changed.isEmpty()) return;
        System.out.println("Reclassified against entity mappings:");
        for (SchemaChange c : changed) {
            System.out.println("  " + c);
            System.out.println("      " + c.getNote());
        }
        System.out.println();
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * console(기본): 콘솔 리포트 + (--report-path 지정 시) JSON 리포트
     * json        : JSON 리포트만 (경로 미지정 시 기본 경로)
     */
    private List<ReportGenerator> reporters(SqlMigrationParser.ParseResult parsed,
                                            List<String> sourceParseFailures, String jsonPath) {
        List<ReportGenerator> list = new ArrayList<>();
        if (!jsonOnly()) {
            list.add(new ConsoleReporter());
        }
        if (jsonPath != null) {
            list.add(new JsonReporter(jsonPath, parsed, sourceParseFailures));
        }
        return list;
    }

    /**
     * 파싱 실패 구문이 있으면 분석 결과와 무관하게 차단한다.
     * 검사 도구가 읽지 못한 변경은 "변경 없음"이 아니라 "알 수 없음"이기 때문이다.
     */
    private int finishWithParseCheck(SqlMigrationParser.ParseResult parsed, int exitCode) {
        if (!parsed.hasFailures()) {
            return exitCode;
        }
        System.err.println();
        System.err.printf("[BLOCKED] %d of %d SQL statement(s) could not be parsed; "
                        + "the analysis above is incomplete. Exit code %d.%n",
                parsed.failures().size(), parsed.totalStatements(), EXIT_PARSE_FAILURE);
        return EXIT_PARSE_FAILURE;
    }

    private void printParseFailures(SqlMigrationParser.ParseResult parsed, String migrationPath) {
        for (SqlMigrationParser.ParseFailure f : parsed.failures()) {
            String stmt = f.statement().replaceAll("\\s+", " ");
            if (stmt.length() > 120) stmt = stmt.substring(0, 117) + "...";
            System.err.printf("  [WARN] Unparsed statement at %s:%d%n", migrationPath, f.line());
            System.err.println("         SQL   : " + stmt);
            System.err.println("         Reason: " + f.reason());
        }
    }

}
