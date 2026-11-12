package schemaguard.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 작은 Spring 프로젝트(users 테이블 → GET /me)를 임시 폴더에 만들어
 * CLI 종료 코드와 JSON 리포트를 끝까지 검증한다.
 */
class SchemaGuardCLITest {

    @TempDir
    Path tmp;

    private Path src;

    @BeforeEach
    void createSampleProject() throws IOException {
        src = tmp.resolve("src/main/java");
        write("com/example/User.java", """
                package com.example;
                @Entity
                @Table(name = "users")
                public class User {
                    @Id private Long id;
                    @Column(name = "email") private String email;
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
    }

    private void write(String rel, String content) throws IOException {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Path sql(String content) throws IOException {
        Path p = tmp.resolve("V2__test.sql");
        Files.writeString(p, content);
        return p;
    }

    private int run(Path migration, String... extra) {
        String[] base = {"--source", src.toString(), "--migration", migration.toString()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new CommandLine(new SchemaGuardCLI()).execute(args);
    }

    private JsonNode readJson(Path p) throws IOException {
        assertTrue(Files.exists(p), "report not written: " + p);
        return new ObjectMapper().readTree(p.toFile());
    }

    @Test
    void highRiskDropColumnExitsOneAndReportsApi() throws IOException {
        Path report = tmp.resolve("out/report.json");   // 없는 디렉터리도 만들어야 한다

        int code = run(sql("ALTER TABLE users DROP COLUMN email;"),
                "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        JsonNode json = readJson(report);
        assertTrue(json.at("/summary/hasHighRisk").asBoolean());
        assertTrue(json.at("/summary/blocked").asBoolean());
        assertEquals("GET /me", json.at("/affectedApis/0/apiId").asText());
    }

    @Test
    void parseFailureExitsThreeAndIsRecordedInJson() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("""
                ALTER TABLE users ADD COLUMN nickname VARCHAR(20);
                ALTER TABLE users FROBNICATE email;
                """), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_PARSE_FAILURE, code);
        JsonNode json = readJson(report);
        assertEquals(1, json.at("/summary/parseFailures").asInt());
        assertFalse(json.at("/summary/analysisComplete").asBoolean());
        assertTrue(json.at("/summary/blocked").asBoolean());
        assertEquals(2, json.at("/parse/failures/0/line").asInt());
    }

    @Test
    void noChangesStillWritesReportAndExitsZero() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("-- nothing\n"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);
        JsonNode json = readJson(report);
        assertFalse(json.at("/summary/blocked").asBoolean());
        assertTrue(json.at("/summary/analysisComplete").asBoolean());
    }

    @Test
    void renameTableReachesApiAsHighRisk() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users RENAME TO members;"),
                "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        JsonNode json = readJson(report);
        assertEquals("GET /me", json.at("/affectedApis/0/apiId").asText());
        assertEquals("RENAME_TABLE", json.at("/affectedApis/0/cause/type").asText());
    }

    @Test
    void setNotNullReachesOnlyWritePathAsMediumRisk() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users ALTER COLUMN email SET NOT NULL;"),
                "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);   // MEDIUM은 차단하지 않는다
        JsonNode json = readJson(report);
        // NOT NULL 은 읽기(GET /me)를 깨지 않는다. NULL 을 저장할 수 있는 쓰기 경로만 영향.
        assertEquals(1, json.at("/summary/medium").asInt());
        assertEquals("POST /signup", json.at("/affectedApis/0/apiId").asText());
        assertEquals("ADD_NOT_NULL", json.at("/affectedApis/0/cause/type").asText());
        assertEquals("col:users.email", json.at("/affectedApis/0/impactPath/0/id").asText());
        assertEquals("repo:UserRepository.save", json.at("/affectedApis/0/impactPath/1/id").asText());
    }

    @Test
    void modifyCompatibleWithEntityMappingHasNoImpact() throws IOException {
        Path report = tmp.resolve("report.json");

        // 엔티티는 String email (length 기본 255, nullable). 같은 길이로 바꾸는 MODIFY 는 영향 없음.
        int code = run(sql("ALTER TABLE users MODIFY email VARCHAR(255);"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);
        JsonNode json = readJson(report);
        assertEquals(0, json.at("/affectedApis").size());
        assertEquals("[LOW] MODIFY_COMPATIBLE on users.email", json.at("/parse/detectedChanges/0").asText());
    }

    @Test
    void narrowingColumnIsMediumOnWritePathWithNote() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users MODIFY email VARCHAR(20);"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);
        JsonNode json = readJson(report);
        assertEquals("POST /signup", json.at("/affectedApis/0/apiId").asText());
        assertEquals("NARROW_COLUMN", json.at("/affectedApis/0/cause/type").asText());
        assertTrue(json.at("/affectedApis/0/cause/note").asText().contains("21~255"));
    }

    @Test
    void incompatibleTypeChangeStaysHighOnReadPath() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users MODIFY email INT;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        JsonNode json = readJson(report);
        assertEquals("GET /me", json.at("/affectedApis/0/apiId").asText());
        assertEquals("MODIFY_TYPE", json.at("/affectedApis/0/cause/type").asText());
    }

    @Test
    void droppingForeignKeyConstraintDoesNotBlock() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users DROP FOREIGN KEY fk_users_team;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);    // 제약만 사라짐: 쿼리는 깨지지 않는다
        JsonNode json = readJson(report);
        assertEquals(0, json.at("/affectedApis").size());
    }

    // ── 인덱스·UNIQUE 삭제 ─────────────────────────────────────────────────────
    // 고치기 전에는 아래 마이그레이션이 모두 종료 코드 0으로 통과했다.

    /** V2__test.sql 과 같은 폴더의 이전 마이그레이션. MigrationHistory 가 버전 순서로 읽는다. */
    private void history(String name, String content) throws IOException {
        Files.writeString(tmp.resolve(name), content);
    }

    @Test
    void droppingUniqueFromHistoryBlocksOnWritePathOnly() throws IOException {
        history("V1__init.sql", """
                CREATE TABLE users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    email VARCHAR(100) NOT NULL UNIQUE
                );
                """);
        Path report = tmp.resolve("report.json");

        int code = run(sql("DROP INDEX email ON users;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        JsonNode json = readJson(report);
        // 중복 저장이 오류 없이 성공한다: 쓰기 경로가 영향. 기존 값을 읽는 GET /me 는 깨지지 않는다.
        assertEquals(1, json.at("/affectedApis").size(), json.toPrettyString());
        assertEquals("POST /signup", json.at("/affectedApis/0/apiId").asText());
        assertEquals("DROP_UNIQUE", json.at("/affectedApis/0/cause/type").asText());
        assertEquals("email", json.at("/affectedApis/0/cause/indexName").asText());
        assertEquals("col:users.email", json.at("/affectedApis/0/impactPath/0/id").asText());
        assertEquals("[HIGH] DROP_UNIQUE on users.email (INDEX: email)",
                json.at("/parse/detectedChanges/0").asText());
    }

    @Test
    void droppingPlainIndexFromHistoryDoesNotBlock() throws IOException {
        history("V1__init.sql", """
                CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100));
                CREATE INDEX idx_users_email ON users (email);
                """);
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users DROP INDEX idx_users_email;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);
        assertEquals(0, readJson(report).at("/affectedApis").size());
    }

    @Test
    void droppingUnknownIndexIsConservativelyBlocked() throws IOException {
        // 이전 마이그레이션도 엔티티도 이 인덱스를 모른다 → UNIQUE 일 수 있다.
        Path report = tmp.resolve("report.json");

        int code = run(sql("DROP INDEX uq_users_email ON users;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
        assertEquals("DROP_UNIQUE", readJson(report).at("/affectedApis/0/cause/type").asText());
    }

    @Test
    void droppingIndexOfUnknownTableIsIncompleteAnalysis() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("DROP INDEX uq_users_email;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_PARSE_FAILURE, code);
        JsonNode json = readJson(report);
        assertFalse(json.at("/summary/analysisComplete").asBoolean());
        assertTrue(json.at("/summary/blocked").asBoolean());
    }

    @Test
    void laterMigrationsAreNotHistory() throws IOException {
        // V3 은 V2 이후에 적용된다. V2 시점에는 idx_users_email 이 일반 인덱스라는 근거가 없다.
        history("V3__later.sql", "CREATE INDEX idx_users_email ON users (email);");
        Path report = tmp.resolve("report.json");

        int code = run(sql("DROP INDEX idx_users_email ON users;"), "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, code);
    }

    @Test
    void consoleModeWithoutReportPathDoesNotWriteJson() throws IOException {
        Path cwdDefault = Path.of("schemaguard-report.json");
        boolean existedBefore = Files.exists(cwdDefault);

        run(sql("ALTER TABLE users ADD COLUMN age INT;"));

        assertEquals(existedBefore, Files.exists(cwdDefault));
    }
}
