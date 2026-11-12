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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 비관적 락 + 인덱스 삭제 (CHANGELOG 18).
 *
 * 재고 차감 시나리오: StockRepository.findByProductIdAndStatus 가 @Lock(PESSIMISTIC_WRITE) 이고,
 * V1 이 (product_id, status) 인덱스를 만든다. 그 인덱스를 지우면 MySQL InnoDB 는 재고 차감 요청마다
 * 테이블을 훑으며 잠근다. SQL 오류도 API 오류도 없다.
 *
 * 대조군: 대체 인덱스가 남는 삭제, PK 로만 잠그는 조회, 잠금이 없는 조회, 그 리포지토리를 쓰지 않는 API.
 */
class LockScopeTest {

    @TempDir
    Path tmp;

    private Path src;
    private Path migrations;

    private static final String V1 = """
            CREATE TABLE products (id BIGINT PRIMARY KEY, name VARCHAR(100));
            CREATE TABLE stocks (
                id BIGINT PRIMARY KEY,
                product_id BIGINT NOT NULL,
                status VARCHAR(20) NOT NULL,
                quantity INT NOT NULL
            );
            CREATE INDEX idx_stocks_product_status ON stocks (product_id, status);
            CREATE INDEX idx_stocks_status ON stocks (status);
            """;

    /** 대체 인덱스 후보: 잠금 조건 (product_id, status) 를 앞 2개로 가진 UNIQUE */
    private static final String SUPERSET_UNIQUE =
            "CREATE UNIQUE INDEX uq_stocks_product_status_qty ON stocks (product_id, status, quantity);\n";

    @BeforeEach
    void createProject() throws IOException {
        src = tmp.resolve("src/main/java");
        write("com/shop/Product.java", """
                package com.shop;
                @Entity
                @Table(name = "products")
                public class Product {
                    @Id private Long id;
                    @Column(name = "name") private String name;
                }
                """);
        write("com/shop/Stock.java", """
                package com.shop;
                @Entity
                @Table(name = "stocks")
                public class Stock {
                    @Id private Long id;
                    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id") private Product product;
                    @Column(name = "status") private String status;
                    @Column(name = "quantity") private int quantity;
                    public int getQuantity() { return quantity; }
                }
                """);
        write("com/shop/StockRepository.java", """
                package com.shop;
                public interface StockRepository extends JpaRepository<Stock, Long> {
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    Optional<Stock> findByProductIdAndStatus(Long productId, String status);

                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    Optional<Stock> findById(Long id);

                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s where s.product.id = :productId")
                    List<Stock> lockAllOfProduct(Long productId);

                    List<Stock> findByStatus(String status);
                }
                """);
        write("com/shop/StockService.java", """
                package com.shop;
                @Service
                public class StockService {
                    private StockRepository stockRepository;
                    public void decrease(Long productId) { stockRepository.findByProductIdAndStatus(productId, "ON_SALE"); }
                    public void adjust(Long id) { stockRepository.findById(id); }
                    public Object onSale() { return stockRepository.findByStatus("ON_SALE"); }
                    public void restock(Long productId) { stockRepository.lockAllOfProduct(productId); }
                    public void create(Stock s) { stockRepository.save(s); }
                }
                """);
        write("com/shop/StockController.java", """
                package com.shop;
                @RestController
                public class StockController {
                    private StockService stockService;
                    @PostMapping("/stocks/decrease")
                    public void decrease() { stockService.decrease(1L); }
                    @PostMapping("/stocks/{id}/adjust")
                    public void adjust() { stockService.adjust(1L); }
                    @GetMapping("/stocks/on-sale")
                    public Object onSale() { return stockService.onSale(); }
                    @PostMapping("/stocks/restock")
                    public void restock() { stockService.restock(1L); }
                    @PostMapping("/stocks")
                    public void create() { stockService.create(null); }
                }
                """);
        write("com/shop/ProductRepository.java", """
                package com.shop;
                public interface ProductRepository extends JpaRepository<Product, Long> {
                    Product findByName(String name);
                }
                """);
        write("com/shop/ProductController.java", """
                package com.shop;
                @RestController
                public class ProductController {
                    private ProductRepository productRepository;
                    @GetMapping("/products")
                    public Object find() { return productRepository.findByName("x"); }
                }
                """);
        migrations = tmp.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__init.sql"), V1);
    }

    private void write(String rel, String content) throws IOException {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private record Run(int code, JsonNode json) {
        List<String> apis(String type) {
            List<String> out = new ArrayList<>();
            json.at("/affectedApis").forEach(a -> {
                if (a.at("/cause/type").asText().equals(type)) out.add(a.at("/apiId").asText() + " " + a.at("/riskLevel").asText());
            });
            return out;
        }
        String note(String type) {
            for (JsonNode a : json.at("/affectedApis")) if (a.at("/cause/type").asText().equals(type)) return a.at("/cause/note").asText();
            return null;
        }
        List<String> detected() {
            List<String> out = new ArrayList<>();
            json.at("/parse/detectedChanges").forEach(n -> out.add(n.asText()));
            return out;
        }
    }

    private Run run(String sql, boolean history, String... extra) throws IOException {
        Path migration = migrations.resolve("V2__change.sql");
        Files.writeString(migration, sql);
        Path report = tmp.resolve("report-" + System.nanoTime() + ".json");
        List<String> args = new ArrayList<>(List.of("--source", src.toString(), "--migration", migration.toString(),
                "--output", "json", "--report-path", report.toString()));
        if (history) args.addAll(List.of("--history", migrations.toString()));
        args.addAll(List.of(extra));
        int code = new CommandLine(new SchemaGuardCLI()).execute(args.toArray(String[]::new));
        return new Run(code, new ObjectMapper().readTree(report.toFile()));
    }

    // ── 끄기 ───────────────────────────────────────────────────────────────

    @Test
    void lockScopeCanBeTurnedOff() throws IOException {
        Run on = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        Run off = run("DROP INDEX idx_stocks_product_status ON stocks;", true, "--no-lock-scope");
        assertFalse(on.apis("LOCK_SCOPE_WIDENED").isEmpty());
        assertEquals(List.of(), off.apis("LOCK_SCOPE_WIDENED"));
        // 끄면 18번 이전과 같다: 일반 인덱스 삭제 LOW 하나만
        assertEquals(List.of("[LOW] DROP_INDEX on stocks.product_id (INDEX: idx_stocks_product_status)"), off.detected());
        assertEquals(SchemaGuardCLI.EXIT_OK, off.code());
        // 명시적으로 켜도 기본값과 같다
        assertEquals(on.detected(), run("DROP INDEX idx_stocks_product_status ON stocks;", true, "--lock-scope").detected());
    }

    // ── 위험이 있는 삭제 ─────────────────────────────────────────────────────

    @Test
    void droppingTheLockQueryIndexWarnsOnlyTheLockingApi() throws IOException {
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);

        // 경고(MEDIUM)라 차단하지 않는다
        assertEquals(SchemaGuardCLI.EXIT_OK, r.code());
        assertFalse(r.json().at("/summary/blocked").asBoolean());
        // 파생 쿼리 잠금(decrease)과 @Query 잠금(restock: where s.product.id = :productId) 둘 다
        assertEquals(List.of("POST /stocks/decrease MEDIUM", "POST /stocks/restock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"),
                "잠금 메서드를 거치는 API 만: PK 잠금(adjust), 잠금 없는 조회(on-sale), 다른 테이블(products)은 아님");
        assertEquals(List.of(
                "[LOW] DROP_INDEX on stocks.product_id (INDEX: idx_stocks_product_status)",
                "[MEDIUM] LOCK_SCOPE_WIDENED on stocks.product_id (INDEX: idx_stocks_product_status) (LOCK: StockRepository.findByProductIdAndStatus)",
                "[MEDIUM] LOCK_SCOPE_WIDENED on stocks.product_id (INDEX: idx_stocks_product_status) (LOCK: StockRepository.lockAllOfProduct)"),
                r.detected());

        String note = r.note("LOCK_SCOPE_WIDENED");
        assertTrue(note.contains("StockRepository.findByProductIdAndStatus (PESSIMISTIC_WRITE, 조건: product_id, status)"), note);
        JsonNode api = firstLockApi(r);
        assertEquals("StockRepository.findByProductIdAndStatus", api.at("/cause/lockMethod").asText());
        assertEquals("PESSIMISTIC_WRITE", api.at("/cause/lockMode").asText());
        assertEquals("MEDIUM", api.at("/riskLevel").asText());
        JsonNode path = api.at("/impactPath");
        assertEquals("col:stocks.product_id", path.get(0).at("/id").asText());
        assertEquals("repo:StockRepository.findByProductIdAndStatus", path.get(1).at("/id").asText());
    }

    private static JsonNode firstLockApi(Run r) {
        for (JsonNode a : r.json().at("/affectedApis")) if (a.at("/cause/type").asText().equals("LOCK_SCOPE_WIDENED")) return a;
        throw new AssertionError("no LOCK_SCOPE_WIDENED api: " + r.json().toPrettyString());
    }

    @Test
    void noReplacementMeansFullScanLocking() throws IOException {
        String note = run("DROP INDEX idx_stocks_product_status ON stocks;", true).note("LOCK_SCOPE_WIDENED");
        // idx_stocks_status (status) 는 남지만 앞 컬럼 status 하나만 맞는다 → 2 → 1
        assertTrue(note.contains("남은 인덱스 idx_stocks_status (status) 로는 앞 1개 컬럼만 쓸 수 있음(삭제 전 2개)"), note);
        assertTrue(note.contains("남은 인덱스 기준: 마이그레이션 이력(--history)"), note);
    }

    @Test
    void droppingBothIndexesLeavesNothingUsable() throws IOException {
        Run r = run("DROP INDEX idx_stocks_status ON stocks;\nDROP INDEX idx_stocks_product_status ON stocks;", true);
        // 잠금 메서드마다 경고 하나: 그 조회가 더 많이 쓰던 (product_id, status) 삭제를 원인으로 한다
        assertEquals(List.of("POST /stocks/decrease MEDIUM", "POST /stocks/restock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        assertEquals(2, r.detected().stream().filter(d -> d.contains("LOCK_SCOPE_WIDENED")).count());
        assertTrue(r.detected().stream().anyMatch(d -> d.contains("LOCK_SCOPE_WIDENED")
                && d.contains("idx_stocks_product_status")), r.detected().toString());
        String note = r.note("LOCK_SCOPE_WIDENED");
        assertTrue(note.contains("남은 인덱스 중 이 조건에 쓸 수 있는 것이 없음"), note);
    }

    @Test
    void replacingWithAShorterIndexIsStillAPartialWidening() throws IOException {
        Run r = run("""
                CREATE INDEX idx_stocks_product ON stocks (product_id);
                DROP INDEX idx_stocks_product_status ON stocks;
                """, true);
        // restock(product_id 하나)은 (product_id) 로 충분하다. decrease 만 부분 확대
        assertEquals(List.of("POST /stocks/decrease MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        assertTrue(r.note("LOCK_SCOPE_WIDENED").contains("idx_stocks_product (product_id) 로는 앞 1개 컬럼만"));
    }

    // ── 대조군: 경고하지 않아야 하는 삭제 ───────────────────────────────────

    @Test
    void columnOrderMattersPerQuery() throws IOException {
        Run r = run("""
                CREATE INDEX idx_stocks_status_product ON stocks (status, product_id);
                DROP INDEX idx_stocks_product_status ON stocks;
                """, true);
        // decrease (product_id =, status =): 둘 다 같음 조건이라 (status, product_id) 로 앞 2개를 쓴다 → 경고 없음
        // restock  (product_id =)          : (status, product_id) 는 맨 앞이 status 라 쓰지 못한다 → 경고
        assertEquals(List.of("POST /stocks/restock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        assertTrue(r.note("LOCK_SCOPE_WIDENED").contains("남은 인덱스 중 이 조건에 쓸 수 있는 것이 없음"));
    }

    @Test
    void equivalentReplacementInTheSameFileDoesNotWarn() throws IOException {
        Run r = run("""
                CREATE INDEX idx_stocks_product_status_v2 ON stocks (product_id, status);
                DROP INDEX idx_stocks_product_status ON stocks;
                """, true);
        assertEquals(List.of(), r.apis("LOCK_SCOPE_WIDENED"), "이름만 바꾼 같은 인덱스");
    }

    @Test
    void existingSupersetUniqueIsAReplacement() throws IOException {
        Files.writeString(migrations.resolve("V1__init.sql"), V1 + SUPERSET_UNIQUE);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        assertEquals(List.of(), r.apis("LOCK_SCOPE_WIDENED"));
    }

    @Test
    void indexTheLockQueryNeverUsedDoesNotWarn() throws IOException {
        // idx_stocks_status (status) 를 지워도 (product_id, status) 가 남는다
        Run r = run("DROP INDEX idx_stocks_status ON stocks;", true);
        assertEquals(SchemaGuardCLI.EXIT_OK, r.code());
        assertEquals(List.of(), r.apis("LOCK_SCOPE_WIDENED"));
        assertEquals(List.of("[LOW] DROP_INDEX on stocks.status (INDEX: idx_stocks_status)"), r.detected());
    }

    @Test
    void jpqlLockQueryIsJudgedFromItsWhereClause() throws IOException {
        // lockAllOfProduct: @Lock + @Query("select s from Stock s where s.product.id = :productId")
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        JsonNode restock = null;
        for (JsonNode a : r.json().at("/affectedApis")) if (a.at("/apiId").asText().equals("POST /stocks/restock")) restock = a;
        assertNotNull(restock, r.json().toPrettyString());
        assertEquals("StockRepository.lockAllOfProduct", restock.at("/cause/lockMethod").asText());
        assertTrue(restock.at("/cause/note").asText().contains(
                "StockRepository.lockAllOfProduct (PESSIMISTIC_WRITE, @Query, 조건: product_id)"), restock.at("/cause/note").asText());
    }

    @Test
    void jpqlLockQueryWithAReplacementDoesNotWarn() throws IOException {
        Files.writeString(migrations.resolve("V1__init.sql"), V1 + SUPERSET_UNIQUE);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        assertEquals(List.of(), r.apis("LOCK_SCOPE_WIDENED"), "UNIQUE (product_id, status, quantity) 가 두 조회를 모두 대신한다");
    }

    // ── 정의를 모를 때 ─────────────────────────────────────────────────────

    @Test
    void withoutHistoryTheDefinitionIsUnknownAndSaysSo() throws IOException {
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", false);
        // 15번 원칙: 정의를 모르면 보수적으로 UNIQUE(HIGH). 잠금 영향은 판단하지 않고 근거에 남긴다.
        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, r.code());
        assertEquals(List.of(), r.apis("LOCK_SCOPE_WIDENED"));
        String note = r.note("DROP_UNIQUE");
        assertTrue(note.contains("비관적 락 조회 3개(StockRepository.findById, StockRepository.findByProductIdAndStatus, "
                + "StockRepository.lockAllOfProduct)가 있지만 인덱스 정의를 몰라 잠금 범위 영향은 판단하지 못함"), note);
        assertTrue(note.contains("--history 를 주면"), note);
    }

    @Test
    void entityDeclaredIndexIsUsedWithoutHistoryAndTheBasisIsStated() throws IOException {
        write("com/shop/Stock.java", """
                package com.shop;
                @Entity
                @Table(name = "stocks", indexes = {
                    @Index(name = "idx_stocks_product_status", columnList = "product_id, status")
                })
                public class Stock {
                    @Id private Long id;
                    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id") private Product product;
                    @Column(name = "status") private String status;
                }
                """);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", false);
        assertEquals(SchemaGuardCLI.EXIT_OK, r.code());
        assertEquals(List.of("POST /stocks/decrease MEDIUM", "POST /stocks/restock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        String note = r.note("LOCK_SCOPE_WIDENED");
        assertTrue(note.contains("남은 인덱스 중 이 조건에 쓸 수 있는 것이 없음"), note);
        assertTrue(note.contains("--history 가 없어 실제 DB 인덱스와 다를 수 있음"), note);
    }

    // ── UNIQUE 삭제에도 잠금 경고가 덧붙는다 ──────────────────────────────────

    @Test
    void droppingAUniqueUsedByTheLockQueryAddsAWarningNextToHigh() throws IOException {
        Files.writeString(migrations.resolve("V1__init.sql"), (V1 + SUPERSET_UNIQUE)
                .replace("CREATE INDEX idx_stocks_product_status ON stocks (product_id, status);\n", "")
                .replace("CREATE INDEX idx_stocks_status ON stocks (status);\n", ""));
        Run r = run("DROP INDEX uq_stocks_product_status_qty ON stocks;", true);
        assertEquals(SchemaGuardCLI.EXIT_HIGH_RISK, r.code());                 // UNIQUE 삭제는 그대로 HIGH (쓰기 경로)
        assertFalse(r.apis("DROP_UNIQUE").isEmpty());
        assertEquals(List.of("POST /stocks/decrease MEDIUM", "POST /stocks/restock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
    }

    // ── 엔티티 속성 → 컬럼 ─────────────────────────────────────────────────

    @Test
    void associationAndUnderscorePropertiesResolveToTheJoinColumn() throws IOException {
        write("com/shop/StockRepository.java", """
                package com.shop;
                public interface StockRepository extends JpaRepository<Stock, Long> {
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    Optional<Stock> findByProductAndStatus(Product product, String status);
                    @Lock(value = PESSIMISTIC_READ)
                    Optional<Stock> findByProduct_IdAndStatus(Long productId, String status);
                    @Lock(LockModeType.OPTIMISTIC)
                    Optional<Stock> findByStatusAndProductId(String status, Long productId);
                }
                """);
        write("com/shop/StockService.java", """
                package com.shop;
                @Service
                public class StockService {
                    private StockRepository stockRepository;
                    public void decrease(Long productId) { stockRepository.findByProductAndStatus(null, "ON_SALE"); }
                    public void read(Long productId) { stockRepository.findByProduct_IdAndStatus(productId, "ON_SALE"); }
                    public void optimistic(Long productId) { stockRepository.findByStatusAndProductId("ON_SALE", productId); }
                }
                """);
        write("com/shop/StockController.java", """
                package com.shop;
                @RestController
                public class StockController {
                    private StockService stockService;
                    @PostMapping("/stocks/decrease")
                    public void decrease() { stockService.decrease(1L); }
                    @GetMapping("/stocks/read")
                    public void read() { stockService.read(1L); }
                    @GetMapping("/stocks/optimistic")
                    public void optimistic() { stockService.optimistic(1L); }
                }
                """);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        // OPTIMISTIC 은 SELECT 에 잠금을 걸지 않는다
        assertEquals(List.of("GET /stocks/read MEDIUM", "POST /stocks/decrease MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
    }
    // ── 2단계: @Query 잠금 조회 ─────────────────────────────────────────────

    /** 리포지토리를 바꾼다. 메서드마다 같은 이름의 서비스·컨트롤러 메서드(POST /q/<메서드>)로 이어 준다. */
    private void queryRepository(String... methods) throws IOException {
        StringBuilder repo = new StringBuilder("package com.shop;\npublic interface StockRepository extends JpaRepository<Stock, Long> {\n");
        StringBuilder svc = new StringBuilder("package com.shop;\n@Service\npublic class StockService {\n    private StockRepository stockRepository;\n");
        StringBuilder ctrl = new StringBuilder("package com.shop;\n@RestController\npublic class StockController {\n    private StockService stockService;\n");
        for (String m : methods) {
            repo.append(m).append('\n');
            String name = m.replaceAll("(?s).*\\s(\\w+)\\(.*", "$1");
            svc.append("    public void ").append(name).append("() { stockRepository.").append(name).append("(null, null); }\n");
            ctrl.append("    @PostMapping(\"/q/").append(name).append("\")\n    public void ").append(name)
                    .append("() { stockService.").append(name).append("(); }\n");
        }
        // 저장 경로: UNIQUE 삭제의 근거(note)를 리포트에서 보려고
        svc.append("    public void create() { stockRepository.save(null); }\n");
        ctrl.append("    @PostMapping(\"/stocks\")\n    public void create() { stockService.create(); }\n");
        write("com/shop/StockRepository.java", repo.append("}\n").toString());
        write("com/shop/StockService.java", svc.append("}\n").toString());
        write("com/shop/StockController.java", ctrl.append("}\n").toString());
    }

    @Test
    void jpqlJoinAliasPkConditionAndOtherTableAttribute() throws IOException {
        queryRepository("""
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s join s.product p where p.id = :pid and s.status = :st")
                    Optional<Stock> viaJoinAlias(Long pid, String st);
                """, """
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s where s.id = :id")
                    Optional<Stock> byPk(Long id, Long unused);
                """, """
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s join s.product p where p.name = :name")
                    List<Stock> byProductName(String name, Long unused);
                """, """
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s where s.tags is empty")
                    List<Stock> unreadable(Long a, Long b);
                """);
        Files.writeString(migrations.resolve("V1__init.sql"), V1 + SUPERSET_UNIQUE);
        Run r = run("DROP INDEX uq_stocks_product_status_qty ON stocks;\nDROP INDEX idx_stocks_product_status ON stocks;", true);

        // 조인 별칭 p.id → product 조인 컬럼(product_id). PK 조회와 다른 테이블 속성 조회는 경고 없음
        assertEquals(List.of("POST /q/viaJoinAlias MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        String note = r.note("DROP_UNIQUE");
        assertTrue(note.contains("StockRepository.byProductName(속성을 컬럼으로 못 바꿈)"), note);
        assertTrue(note.contains("StockRepository.unreadable(@Query 해석 실패: WHERE 를 읽지 못함: s.tags is empty)"), note);
        assertFalse(note.contains("byPk("), "PK 조회는 판정했고 경고가 없을 뿐이다: " + note);
    }

    @Test
    void jpqlOrBranchesTakeTheWorstBranch() throws IOException {
        queryRepository("""
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s where s.id = :id or (s.product.id = :pid and s.status = :st)")
                    List<Stock> pkOrProduct(Long id, Long pid);
                """);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        // PK 갈래는 안전하지만 다른 갈래가 (product_id, status) 를 잃는다
        assertEquals(List.of("POST /q/pkOrProduct MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
    }

    @Test
    void nativeForUpdateIsALockEvenWithoutAtLock() throws IOException {
        queryRepository("""
                    @Query(value = "SELECT * FROM stocks s WHERE s.product_id = :pid AND s.status = :st FOR UPDATE", nativeQuery = true)
                    Optional<Stock> nativeForUpdate(Long pid, String st);
                """, """
                    @Query(value = "SELECT * FROM stocks WHERE product_id = :pid AND status = :st", nativeQuery = true)
                    Optional<Stock> nativeNoLock(Long pid, String st);
                """, """
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query(value = "SELECT * FROM stocks WHERE product_id = :pid AND status = :st", nativeQuery = true)
                    Optional<Stock> nativeAtLockOnly(Long pid, String st);
                """);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        assertEquals(List.of("POST /q/nativeAtLockOnly MEDIUM", "POST /q/nativeForUpdate MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"),
                "잠금이 없는 네이티브 조회(nativeNoLock)는 대상이 아니다");
        for (JsonNode a : r.json().at("/affectedApis")) {
            String api = a.at("/apiId").asText();
            if (api.equals("POST /q/nativeForUpdate")) assertEquals("FOR UPDATE (네이티브 SQL)", a.at("/cause/lockMode").asText());
            if (api.equals("POST /q/nativeAtLockOnly"))
                assertTrue(a.at("/cause/note").asText().contains("네이티브 쿼리의 @Lock 은 JPA 구현에 따라 적용되지 않을 수 있음"));
        }
    }

    @Test
    void concatenatedAndTextBlockQueriesAreRead() throws IOException {
        queryRepository("""
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query("select s from Stock s " +
                           "where s.product.id = :pid " +
                           "and s.status = :st")
                    Optional<Stock> concatenated(Long pid, String st);
                """, """
                    @Lock(LockModeType.PESSIMISTIC_WRITE)
                    @Query(\"""
                            select s from Stock s
                            where s.product.id = :pid
                              and s.status = :st
                            \""")
                    Optional<Stock> textBlock(Long pid, String st);
                """);
        Run r = run("DROP INDEX idx_stocks_product_status ON stocks;", true);
        assertEquals(List.of("POST /q/concatenated MEDIUM", "POST /q/textBlock MEDIUM"), r.apis("LOCK_SCOPE_WIDENED"));
        assertEquals(0, r.json().at("/parse/sourceParseFailures").size(), "텍스트 블록이 있는 파일도 파싱해야 한다");
    }
}
