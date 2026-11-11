package schemaguard.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import schemaguard.analyzer.ImpactAnalyzer;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.ImpactResult;
import schemaguard.parser.MigrationParser;
import schemaguard.parser.SourceScanner;
import schemaguard.report.JsonReporter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 같은 입력이면 같은 결과(결정성)를 고정하는 테스트.
 *
 *  1) 스냅샷: 샘플 프로젝트 + 마이그레이션의 JSON 리포트를 정답 파일과 바이트 단위로 비교한다.
 *     의도적으로 결과를 바꿨다면 정답 파일을 다시 만든다:
 *       ./gradlew test --tests '*DeterminismTest*' -PupdateSnapshots=true
 *  2) 순서 무관성: Java 파일 입력 순서를 섞어도(=OS마다 다른 listFiles 순서) 리포트가 같아야 한다.
 *
 * 샘플은 일부러 "같은 API에 닿는 경로가 여러 개"인 구조로 만든다.
 * (GET /admin/users 는 AuditService 와 UserService 두 경로로 users.email 에 닿는다)
 */
class DeterminismTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/snapshots/determinism-report.json");

    private static final String MIGRATION = """
            ALTER TABLE users DROP COLUMN email;
            ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;
            ALTER TABLE users ADD COLUMN age INT;
            RENAME TABLE users TO members;
            """;

    @TempDir
    Path tmp;

    private Path src;
    private Path migration;

    @BeforeEach
    void createSampleProject() throws IOException {
        src = tmp.resolve("src/main/java");
        write("com/example/user/User.java", """
                package com.example.user;
                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                    @Column(name = "email") private String email;
                    @Column(name = "nickname") private String nickname;
                }
                """);
        write("com/example/user/UserRepository.java", """
                package com.example.user;
                public interface UserRepository extends JpaRepository<User, Long> {
                    User findByEmail(String email);
                    User findByNickname(String nickname);
                }
                """);
        write("com/example/user/UserService.java", """
                package com.example.user;
                @Service
                public class UserService {
                    private UserRepository userRepository;
                    private AuditService auditService;
                    public User getByEmail(String e) {
                        auditService.record(e);
                        return userRepository.findByEmail(e);
                    }
                    public User getByNickname(String n) { return userRepository.findByNickname(n); }
                }
                """);
        write("com/example/user/AuditService.java", """
                package com.example.user;
                @Service
                public class AuditService {
                    private UserRepository userRepository;
                    public void record(String e) { userRepository.findByEmail(e); }
                }
                """);
        write("com/example/user/UserController.java", """
                package com.example.user;
                @RestController
                public class UserController {
                    private UserService userService;
                    @GetMapping("/me")
                    public User me() { return userService.getByEmail("x"); }
                    @GetMapping("/users/by-nickname")
                    public User byNickname() { return userService.getByNickname("n"); }
                }
                """);
        write("com/example/admin/AdminController.java", """
                package com.example.admin;
                @RestController
                public class AdminController {
                    private AuditService auditService;
                    private UserService userService;
                    @GetMapping("/admin/users")
                    public Object users() {
                        auditService.record("a");
                        return userService.getByEmail("a");
                    }
                }
                """);
        write("com/example/post/Post.java", """
                package com.example.post;
                @Entity
                @Table(name = "posts")
                public class Post {
                    @Id private Long id;
                    @Column(name = "title") private String title;
                    @ManyToOne @JoinColumn(name = "author_id") private User author;
                }
                """);
        write("com/example/post/PostRepository.java", """
                package com.example.post;
                public interface PostRepository extends JpaRepository<Post, Long> {
                    java.util.List<Post> findByAuthor(User author);
                }
                """);
        write("com/example/post/PostService.java", """
                package com.example.post;
                @Service
                public class PostService {
                    private PostRepository postRepository;
                    public Object list() { return postRepository.findByAuthor(null); }
                }
                """);
        write("com/example/post/PostController.java", """
                package com.example.post;
                @RestController
                public class PostController {
                    private PostService postService;
                    @GetMapping("/posts")
                    public Object list() { return postService.list(); }
                }
                """);
        // 파싱 실패 파일: 리포트의 sourceParseFailures 경로가 OS와 무관하게 같아야 한다.
        write("com/example/broken/Broken.java", "public class Broken { void x( }");

        migration = tmp.resolve("V9__determinism.sql");
        Files.writeString(migration, MIGRATION);
    }

    private void write(String rel, String content) throws IOException {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    // ── 1. 스냅샷 ────────────────────────────────────────────────────────────

    @Test
    void reportMatchesSnapshot() throws IOException {
        Path report = tmp.resolve("report.json");
        int code = new CommandLine(new SchemaGuardCLI()).execute(
                "--source", src.toString(), "--migration", migration.toString(),
                "--output", "json", "--report-path", report.toString());
        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);

        String actual = Files.readString(report, StandardCharsets.UTF_8);
        assertFalse(actual.contains("\r"), "report must use LF line endings on every OS");

        if (Boolean.getBoolean("schemaguard.updateSnapshots")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
            System.out.println("Snapshot updated: " + SNAPSHOT.toAbsolutePath());
            return;
        }
        assertTrue(Files.exists(SNAPSHOT),
                "snapshot missing; run: ./gradlew test --tests '*DeterminismTest*' -PupdateSnapshots=true");
        // git autocrlf 로 정답 파일이 CRLF 로 체크아웃되어도 비교가 깨지지 않게 한다.
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(expected, actual,
                "report changed; if intended, regenerate with -PupdateSnapshots=true and review the diff");
    }

    @Test
    void repeatedRunsProduceIdenticalReports() throws IOException {
        String first = null;
        for (int i = 0; i < 3; i++) {
            Path report = tmp.resolve("run-" + i + ".json");
            new CommandLine(new SchemaGuardCLI()).execute(
                    "--source", src.toString(), "--migration", migration.toString(),
                    "--output", "json", "--report-path", report.toString());
            String json = Files.readString(report, StandardCharsets.UTF_8);
            if (first == null) first = json;
            else assertEquals(first, json, "run " + i + " differs from run 0");
        }
    }

    // ── 2. 입력 순서 무관성 ──────────────────────────────────────────────────

    @Test
    void javaFileOrderDoesNotChangeReport() throws Exception {
        List<File> files = SourceScanner.collectJavaFiles(src.toFile());
        String sorted = analyzeToJson(files, "sorted");

        for (long seed = 1; seed <= 20; seed++) {
            List<File> shuffled = new ArrayList<>(files);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(sorted, analyzeToJson(shuffled, "seed-" + seed),
                    "report differs for file order seed " + seed + ": " + shuffled);
        }
        List<File> reversed = new ArrayList<>(files);
        Collections.reverse(reversed);
        assertEquals(sorted, analyzeToJson(reversed, "reversed"));
    }

    @Test
    void collectJavaFilesReturnsPathSortedList() {
        List<File> files = SourceScanner.collectJavaFiles(src.toFile());
        List<String> paths = files.stream().map(f -> f.getPath().replace('\\', '/')).toList();
        List<String> expected = new ArrayList<>(paths);
        Collections.sort(expected);
        assertEquals(expected, paths);
    }

    /** CLI 와 같은 파이프라인을 주어진 파일 순서로 돌려 JSON 리포트 문자열을 얻는다. */
    private String analyzeToJson(List<File> javaFiles, String name) throws Exception {
        MigrationParser.ParseResult parsed = new MigrationParser().parse(migration.toFile());
        SourceScanner.Result scan = new SourceScanner().scan(javaFiles);

        DependencyGraphBuilder builder = new DependencyGraphBuilder();
        builder.setSourceRoot(src.toString());
        builder.build(scan.entities(), scan.fkMappings(), scan.repositories(),
                scan.services(), scan.controllers());
        List<schemaguard.model.SchemaChange> classified =
                new schemaguard.analyzer.ChangeClassifier().classify(parsed.changes(), scan.entities(), scan.fkMappings());
        ImpactResult result = ImpactAnalyzer.of(builder).analyze(classified);

        Path out = tmp.resolve("order-" + name + ".json");
        MigrationParser.ParseResult reported =
                new MigrationParser.ParseResult(classified, parsed.failures(), parsed.totalStatements());
        new JsonReporter(out.toString(), reported, List.of()).generate(result);
        return Files.readString(out, StandardCharsets.UTF_8);
    }
}
