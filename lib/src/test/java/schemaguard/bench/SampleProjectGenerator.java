package schemaguard.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 벤치마크용 Spring Boot 샘플 프로젝트를 만든다.
 *
 * 파일 4개(Entity, Repository, Service, Controller)가 한 그룹이고, 그룹 k는
 *   - 테이블 t{k}(id, email, name, parent_id → t{k-1}) 엔티티
 *   - findByEmail / findByName 리포지토리 (findByEmail 은 @EntityGraph 로 LAZY parent 를 함께 로딩)
 *   - 자기 리포지토리와 다음 그룹 리포지토리를 호출하는 서비스
 *   - GET 엔드포인트 2개를 가진 컨트롤러
 * 로 구성된다. 실제 코드와 비슷한 파싱 부하를 주기 위해 파일마다 보조 메서드를 넣는다.
 */
public final class SampleProjectGenerator {

    private static final int HELPER_METHODS_PER_FILE = 8;

    private SampleProjectGenerator() {}

    /** @return 생성한 마이그레이션 SQL 경로 */
    public static Path generate(Path root, int fileCount) throws IOException {
        int groups = Math.max(1, fileCount / 4);
        Path src = root.resolve("src/main/java");

        for (int k = 0; k < groups; k++) {
            String pkg = "com.example.g" + (k / 100) + ".m" + k;
            Path dir = src.resolve(pkg.replace('.', '/'));
            Files.createDirectories(dir);
            int next = (k + 1) % groups;
            String nextPkg = "com.example.g" + (next / 100) + ".m" + next;

            Files.writeString(dir.resolve("E" + k + ".java"), entity(pkg, k));
            Files.writeString(dir.resolve("E" + k + "Repository.java"), repository(pkg, k));
            Files.writeString(dir.resolve("E" + k + "Service.java"), service(pkg, k, next, nextPkg));
            Files.writeString(dir.resolve("E" + k + "Controller.java"), controller(pkg, k));
        }

        // 일부 테이블의 컬럼 삭제 + 타입 변경 + 테이블명 변경 → BFS가 실제로 일하도록
        StringBuilder sql = new StringBuilder();
        for (int k = 0; k < Math.min(10, groups); k++) {
            sql.append("ALTER TABLE t").append(k).append(" DROP COLUMN email;\n");
        }
        sql.append("ALTER TABLE t").append(groups / 2).append(" MODIFY name VARCHAR(10);\n");
        sql.append("ALTER TABLE t").append(groups - 1).append(" RENAME TO t_renamed;\n");

        Path migration = root.resolve("src/main/resources/db/migration/V2__bench.sql");
        Files.createDirectories(migration.getParent());
        Files.writeString(migration, sql.toString());
        return migration;
    }

    private static String entity(String pkg, int k) {
        String parent = k > 0
                ? """
                      @ManyToOne(fetch = FetchType.LAZY)
                      @JoinColumn(name = "parent_id")
                      private %s parent;
                  """.formatted("E" + (k - 1))
                : "";
        String parentImport = k > 0
                ? "import com.example.g" + ((k - 1) / 100) + ".m" + (k - 1) + ".E" + (k - 1) + ";"
                : "";
        return """
                package %s;

                import jakarta.persistence.*;
                %s
                @Entity
                @Table(name = "t%d")
                public class E%d {
                    @Id
                    @GeneratedValue
                    private Long id;

                    @Column(name = "email")
                    private String email;

                    @Column(name = "name")
                    private String name;
                %s
                    public Long getId() { return id; }
                    public String getEmail() { return email; }
                    public String getName() { return name; }
                %s}
                """.formatted(pkg, parentImport, k, k, parent, helpers());
    }

    private static String repository(String pkg, int k) {
        return """
                package %s;

                import java.util.List;
                import java.util.Optional;
                import org.springframework.data.jpa.repository.JpaRepository;

                public interface E%dRepository extends JpaRepository<E%d, Long> {
                    %s
                    Optional<E%d> findByEmail(String email);
                    List<E%d> findByName(String name);
                }
                """.formatted(pkg, k, k,
                        k > 0 ? "@EntityGraph(attributePaths = {\"parent\"})" : "", k, k);
    }

    private static String service(String pkg, int k, int next, String nextPkg) {
        return """
                package %s;

                import org.springframework.stereotype.Service;
                import %s.E%dRepository;

                @Service
                public class E%dService {
                    private final E%dRepository e%dRepository;
                    private final E%dRepository nextRepository;

                    public E%dService(E%dRepository r, E%dRepository n) {
                        this.e%dRepository = r;
                        this.nextRepository = n;
                    }

                    public E%d getByEmail(String email) {
                        return e%dRepository.findByEmail(email).orElseThrow();
                    }

                    public int countByName(String name) {
                        return e%dRepository.findByName(name).size() + nextRepository.findByName(name).size();
                    }
                %s}
                """.formatted(pkg, nextPkg, next, k, k, k, next, k, k, next, k, k, k, k, helpers());
    }

    private static String controller(String pkg, int k) {
        return """
                package %s;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/e%d")
                public class E%dController {
                    private final E%dService e%dService;

                    public E%dController(E%dService s) { this.e%dService = s; }

                    @GetMapping("/by-email")
                    public E%d byEmail(@RequestParam String email) {
                        return e%dService.getByEmail(email);
                    }

                    @GetMapping("/count")
                    public int count(@RequestParam String name) {
                        return e%dService.countByName(name);
                    }
                %s}
                """.formatted(pkg, k, k, k, k, k, k, k, k, k, k, helpers());
    }

    private static String helpers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HELPER_METHODS_PER_FILE; i++) {
            sb.append("""

                        private int helper%d(java.util.List<String> items, int limit) {
                            int total = 0;
                            for (String s : items) {
                                if (s == null || s.isBlank()) continue;
                                total += s.length() > limit ? limit : s.length();
                            }
                            return total * %d;
                        }
                    """.formatted(i, i + 1));
        }
        return sb.toString();
    }
}
