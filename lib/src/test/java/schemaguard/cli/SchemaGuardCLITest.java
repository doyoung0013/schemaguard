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
                }
                """);
        write("com/example/UserController.java", """
                package com.example;
                @RestController
                public class UserController {
                    private UserService userService;
                    @GetMapping("/me")
                    public User me() { return userService.getByEmail("x"); }
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
    void setNotNullReachesApiAsMediumRisk() throws IOException {
        Path report = tmp.resolve("report.json");

        int code = run(sql("ALTER TABLE users ALTER COLUMN email SET NOT NULL;"),
                "--report-path", report.toString());

        assertEquals(SchemaGuardCLI.EXIT_OK, code);   // MEDIUM은 차단하지 않는다
        JsonNode json = readJson(report);
        assertEquals(1, json.at("/summary/medium").asInt());
        assertEquals("ADD_NOT_NULL", json.at("/affectedApis/0/cause/type").asText());
    }

    @Test
    void consoleModeWithoutReportPathDoesNotWriteJson() throws IOException {
        Path cwdDefault = Path.of("schemaguard-report.json");
        boolean existedBefore = Files.exists(cwdDefault);

        run(sql("ALTER TABLE users ADD COLUMN age INT;"));

        assertEquals(existedBefore, Files.exists(cwdDefault));
    }
}
