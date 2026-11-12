package schemaguard.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 여러 마이그레이션을 한 번에 분석(--migration 여러 개 또는 폴더)해도
 * 파일별 리포트가 파일마다 따로 실행한 리포트와 바이트 단위로 같은지 확인한다.
 */
class MultiMigrationTest {

    @TempDir
    Path tmp;

    private Path src;
    private Path migrations;

    @BeforeEach
    void createProject() throws IOException {
        src = tmp.resolve("src/main/java");
        write("com/example/User.java", """
                package com.example;
                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                    @Column(name = "email") private String email;
                    @Column(name = "nickname") private String nickname;
                }
                """);
        write("com/example/UserRepository.java", """
                package com.example;
                public interface UserRepository extends JpaRepository<User, Long> {
                    User findByEmail(String email);
                }
                """);
        write("com/example/UserService.java", """
                package com.example;
                @Service
                public class UserService {
                    private UserRepository userRepository;
                    public User getByEmail(String e) { return userRepository.findByEmail(e); }
                    public User signup(User u) { return userRepository.save(u); }
                }
                """);
        write("com/example/UserController.java", """
                package com.example;
                @RestController
                public class UserController {
                    private UserService userService;
                    @GetMapping("/me")
                    public User me() { return userService.getByEmail("x"); }
                    @PostMapping("/signup")
                    public User signup() { return userService.signup(null); }
                }
                """);
        write("com/example/Broken.java", "public class Broken { void x( }");

        migrations = tmp.resolve("db/migration");
        Files.createDirectories(migrations);
        // --history 를 주면 V1 이 V3·V4 의 이력이다: DROP INDEX 의 UNIQUE 여부를 V1 에서 알아야 한다.
        sql("V1__init.sql", """
                CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100), nickname VARCHAR(50));
                CREATE UNIQUE INDEX uq_users_email ON users (email);
                CREATE INDEX idx_users_nick ON users (nickname);
                """);
        sql("V2__drop_email.sql", "ALTER TABLE users DROP COLUMN email;");          // 1: HIGH
        sql("V3__drop_unique.sql", "DROP INDEX uq_users_email ON users;");          // 1: UNIQUE 삭제
        sql("V4__drop_plain_index.sql", "DROP INDEX idx_users_nick ON users;");     // 0
        sql("V5__bad.sql", "ALTER TABLE users ADD COLUMN age INT;\nTHIS IS NOT SQL;"); // 3
        sql("V10__add_column.sql", "ALTER TABLE users ADD COLUMN memo TEXT;");      // 0, V2 < V10
    }

    private void write(String rel, String content) throws IOException {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void sql(String name, String content) throws IOException {
        Files.writeString(migrations.resolve(name), content);
    }

    private static int cli(List<String> args) {
        return new CommandLine(new SchemaGuardCLI()).execute(args.toArray(String[]::new));
    }

    private int single(Path migration, Path report, String... extra) {
        List<String> args = new ArrayList<>(List.of("--source", src.toString(), "--migration", migration.toString(),
                "--output", "json", "--report-path", report.toString(), "--history", migrations.toString()));
        args.addAll(List.of(extra));
        return cli(args);
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "report not written: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static final List<String> NAMES = List.of(
            "V1__init", "V2__drop_email", "V3__drop_unique", "V4__drop_plain_index", "V5__bad", "V10__add_column");

    @Test
    void folderRunMatchesSeparateRunsByteForByte() throws IOException {
        Path one = tmp.resolve("separate");
        List<Integer> separateCodes = new ArrayList<>();
        for (String n : NAMES) {
            separateCodes.add(single(migrations.resolve(n + ".sql"), one.resolve(n + ".json")));
        }
        assertEquals(List.of(0, 1, 1, 0, 3, 0), separateCodes);

        Path many = tmp.resolve("together");
        int code = cli(List.of("--source", src.toString(), "--migration", migrations.toString(),
                "--output", "json", "--report-dir", many.toString(), "--history", migrations.toString()));

        // 가장 심각한 코드: 분석 불완전(3) > HIGH(1)
        assertEquals(SchemaGuardCLI.EXIT_PARSE_FAILURE, code);
        for (String n : NAMES) {
            assertEquals(read(one.resolve(n + ".json")), read(many.resolve(n + ".json")), n);
        }

        // 파일별 종료 코드. 순서는 파일 이름의 V 번호 순서(V2 < V10)다.
        List<String> rows = Files.readAllLines(many.resolve(SchemaGuardCLI.RESULTS_FILE));
        assertEquals(NAMES.size(), rows.size());
        for (int i = 0; i < NAMES.size(); i++) {
            String[] f = rows.get(i).split("\t");
            assertTrue(f[0].endsWith(NAMES.get(i) + ".sql"), rows.get(i));
            assertEquals(String.valueOf(separateCodes.get(i)), f[1], rows.get(i));
            assertTrue(f[2].endsWith(NAMES.get(i) + ".json"), rows.get(i));
        }
    }

    @Test
    void explicitFilesInAnyOrderAreAnalyzedInVersionOrderWithParallelParsing() throws IOException {
        Path many = tmp.resolve("explicit");
        int code = cli(List.of("--source", src.toString(),
                "--migration", migrations.resolve("V10__add_column.sql").toString(),
                migrations.resolve("V3__drop_unique.sql").toString(),
                "-m", migrations.resolve("V2__drop_email.sql").toString(),
                "-m", migrations.resolve("V2__drop_email.sql").toString(),   // 같은 파일은 한 번만
                "--output", "json", "--report-dir", many.toString(), "--threads", "4",
                "--history", migrations.toString()));
        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);

        List<String> rows = Files.readAllLines(many.resolve(SchemaGuardCLI.RESULTS_FILE));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).contains("V2__drop_email.sql\t1\t"), rows.get(0));
        assertTrue(rows.get(1).contains("V3__drop_unique.sql\t1\t"), rows.get(1));
        assertTrue(rows.get(2).contains("V10__add_column.sql\t0\t"), rows.get(2));

        // 순차 파싱으로 따로 실행한 리포트와 같다.
        for (String n : List.of("V2__drop_email", "V3__drop_unique", "V10__add_column")) {
            Path alone = tmp.resolve("alone-" + n + ".json");
            single(migrations.resolve(n + ".sql"), alone, "--threads", "1");
            assertEquals(read(alone), read(many.resolve(n + ".json")), n);
        }
    }

    @Test
    void missingFileDoesNotStopOthersAndExitsTwo() throws IOException {
        Path many = tmp.resolve("missing");
        int code = cli(List.of("--source", src.toString(),
                "--migration", migrations.resolve("V2__drop_email.sql").toString(),
                migrations.resolve("V7__nope.sql").toString(),
                "--output", "json", "--report-dir", many.toString()));

        assertEquals(SchemaGuardCLI.EXIT_ERROR, code);
        assertTrue(Files.exists(many.resolve("V2__drop_email.json")));
        assertFalse(Files.exists(many.resolve("V7__nope.json")));
        List<String> rows = Files.readAllLines(many.resolve(SchemaGuardCLI.RESULTS_FILE));
        assertTrue(rows.get(0).contains("V2__drop_email.sql\t1\t"), rows.get(0));
        assertTrue(rows.get(1).contains("V7__nope.sql\t2\t"), rows.get(1));
    }

    /** --history 가 없으면 V1 을 읽지 않는다: 일반 인덱스 삭제(V4)도 정의를 몰라 보수적으로 차단된다. */
    @Test
    void withoutHistoryEarlierFilesAreNotRead() throws IOException {
        Path many = tmp.resolve("nohistory");
        int code = cli(List.of("--source", src.toString(), "--migration", migrations.toString(),
                "--output", "json", "--report-dir", many.toString()));
        assertEquals(SchemaGuardCLI.EXIT_PARSE_FAILURE, code);
        List<String> rows = Files.readAllLines(many.resolve(SchemaGuardCLI.RESULTS_FILE));
        assertTrue(rows.get(3).contains("V4__drop_plain_index.sql\t1\t"), rows.get(3));
        assertTrue(read(many.resolve("V4__drop_plain_index.json")).contains("--history"));
    }

    /** CI 에서 바뀐 SQL 이 하나뿐이어도 --report-dir 를 주면 results.tsv 가 있어야 한다. */
    @Test
    void singleFileWithReportDirWritesResultsAndSameReport() throws IOException {
        Path dir = tmp.resolve("one");
        Path file = migrations.resolve("V2__drop_email.sql");
        int code = cli(List.of("--source", src.toString(), "--migration", file.toString(),
                "--output", "json", "--report-dir", dir.toString()));
        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        List<String> rows = Files.readAllLines(dir.resolve(SchemaGuardCLI.RESULTS_FILE));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("V2__drop_email.sql\t1\t"), rows.get(0));

        Path alone = tmp.resolve("alone.json");
        single(file, alone);
        assertEquals(read(alone), read(dir.resolve("V2__drop_email.json")));
    }

    @Test
    void reportPathAndReportDirTogetherAreRejected() {
        int code = cli(List.of("--source", src.toString(),
                "--migration", migrations.resolve("V2__drop_email.sql").toString(),
                "--report-path", tmp.resolve("r.json").toString(), "--report-dir", tmp.resolve("d").toString()));
        assertEquals(SchemaGuardCLI.EXIT_ERROR, code);
        assertFalse(Files.exists(tmp.resolve("r.json")));
    }

    @Test
    void reportPathIsRejectedForSeveralMigrations() {
        int code = cli(List.of("--source", src.toString(), "--migration", migrations.toString(),
                "--report-path", tmp.resolve("r.json").toString()));
        assertEquals(SchemaGuardCLI.EXIT_ERROR, code);
        assertFalse(Files.exists(tmp.resolve("r.json")));
    }

    @Test
    void sameReportNameFromTwoFoldersIsRejected() throws IOException {
        Path other = tmp.resolve("other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("V2__drop_email.sql"), "ALTER TABLE users DROP COLUMN nickname;");
        int code = cli(List.of("--source", src.toString(),
                "--migration", migrations.resolve("V2__drop_email.sql").toString(),
                other.resolve("V2__drop_email.sql").toString(),
                "--output", "json", "--report-dir", tmp.resolve("clash").toString()));
        assertEquals(SchemaGuardCLI.EXIT_ERROR, code);
    }

    @Test
    void emptyFolderIsAnError() throws IOException {
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        assertEquals(SchemaGuardCLI.EXIT_ERROR,
                cli(List.of("--source", src.toString(), "--migration", empty.toString())));
    }

    @Test
    void worstExitCodeWins() {
        assertEquals(1, SchemaGuardCLI.worse(0, 1));
        assertEquals(3, SchemaGuardCLI.worse(3, 1));
        assertEquals(3, SchemaGuardCLI.worse(1, 3));
        assertEquals(2, SchemaGuardCLI.worse(3, 2));
        assertEquals(0, SchemaGuardCLI.worse(0, 0));
    }
}
