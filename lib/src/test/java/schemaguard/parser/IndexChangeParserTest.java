package schemaguard.parser;

import org.junit.jupiter.api.Test;
import schemaguard.model.ChangeType;
import schemaguard.model.RiskLevel;
import schemaguard.model.SchemaChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 인덱스·UNIQUE 제약 삭제 탐지.
 *
 * 고치기 전에는 아래 구문이 모두 "변경 없음" 또는 엉뚱한 변경으로 처리되어 종료 코드 0으로 통과했다.
 *   ALTER TABLE users DROP INDEX uq   → 아무것도 탐지 안 됨 (DROP 분기가 컬럼 이름이 없으면 그냥 지나감)
 *   DROP INDEX uq ON users            → DROP_TABLE on uq (Drop 구문의 대상 종류를 보지 않음)
 */
class IndexChangeParserTest {

    /** V1 에 해당하는 이전 마이그레이션. 이름 있는/없는 UNIQUE, 일반 인덱스, FK, PK 를 모두 담는다. */
    private static final String HISTORY = """
            CREATE TABLE users (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                email VARCHAR(100) NOT NULL UNIQUE,
                nickname VARCHAR(50),
                team_id BIGINT,
                CONSTRAINT uq_users_nickname UNIQUE (nickname, team_id),
                KEY idx_users_team (team_id),
                CONSTRAINT fk_users_team FOREIGN KEY (team_id) REFERENCES teams(id)
            );
            CREATE INDEX idx_users_nick ON users (nickname);
            CREATE UNIQUE INDEX uq_users_ext ON users (team_id, email);
            """;

    private static SqlMigrationParser.ParseResult parseWithHistory(String sql) {
        IndexCatalog catalog = new IndexCatalog();
        catalog.applyAll(HISTORY);
        return new SqlMigrationParser(catalog).parseSQL(sql);
    }

    private static SchemaChange only(SqlMigrationParser.ParseResult r) {
        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        assertEquals(1, r.changes().size(), () -> r.changes().toString());
        return r.changes().get(0);
    }

    // ── 고치기 전 버그 재현 ────────────────────────────────────────────────────

    @Test
    void alterTableDropIndexIsNoLongerSilentlyIgnored() {
        SchemaChange c = only(new SqlMigrationParser().parseSQL("ALTER TABLE users DROP INDEX uq_users_email;"));
        assertEquals(ChangeType.DROP_INDEX, c.getChangeType());
        assertEquals("users", c.getTableName());
        assertEquals("uq_users_email", c.getIndexName());
        assertNull(c.getIndexColumns(), "정의를 모르면 컬럼도 모른다 (분류는 ChangeClassifier 가 마무리)");
        assertEquals(RiskLevel.HIGH, c.getRiskLevel(), "모르는 인덱스는 보수적으로 HIGH");
    }

    @Test
    void dropIndexOnTableIsNotATableDrop() {
        SchemaChange c = only(new SqlMigrationParser().parseSQL("DROP INDEX idx_users_email ON users;"));
        assertEquals(ChangeType.DROP_INDEX, c.getChangeType());
        assertEquals("users", c.getTableName(), "ON 뒤의 테이블이 대상이다 (인덱스 이름이 아니라)");
        assertEquals("idx_users_email", c.getIndexName());
    }

    @Test
    void mysqlDropKeySynonymIsDetected() {
        // JSQLParser 4.7 은 DROP KEY 를 파싱하지 못한다 (전에는 종료 코드 3).
        SchemaChange c = only(new SqlMigrationParser().parseSQL("ALTER TABLE `users` DROP KEY `uq_users_email`;"));
        assertEquals(ChangeType.DROP_INDEX, c.getChangeType());
        assertEquals("users", c.getTableName());
        assertEquals("uq_users_email", c.getIndexName());
    }

    @Test
    void dropIndexWithoutTableAndUnknownDefinitionIsAnalysisFailure() {
        // PostgreSQL 형식. 어느 테이블의 인덱스인지 알 수 없으면 영향 경로를 찾을 수 없다 → "변경 없음"이 아니라 "알 수 없음".
        SqlMigrationParser.ParseResult r = new SqlMigrationParser().parseSQL("DROP INDEX uq_users_email;");
        assertTrue(r.hasFailures());
        assertTrue(r.failures().get(0).reason().contains("uq_users_email"), r.failures().toString());
        assertTrue(r.changes().isEmpty());
    }

    @Test
    void unsupportedDropTargetIsAnalysisFailure() {
        // @SequenceGenerator 를 쓰는 INSERT 가 깨질 수 있다. 모르는 대상은 통과시키지 않는다.
        SqlMigrationParser.ParseResult r = new SqlMigrationParser().parseSQL("DROP SEQUENCE users_seq;");
        assertTrue(r.hasFailures());
    }

    @Test
    void dropViewKeepsTableDropSemantics() {
        // @Entity 가 뷰에 매핑될 수 있다. 뷰를 지우면 테이블을 지운 것과 같은 범위가 깨진다.
        SchemaChange c = only(new SqlMigrationParser().parseSQL("DROP VIEW user_summary;"));
        assertEquals(ChangeType.DROP_TABLE, c.getChangeType());
        assertEquals("user_summary", c.getTableName());
    }

    @Test
    void dropTableStillWorks() {
        SchemaChange c = only(new SqlMigrationParser().parseSQL("DROP TABLE IF EXISTS comments;"));
        assertEquals(ChangeType.DROP_TABLE, c.getChangeType());
        assertEquals("comments", c.getTableName());
    }

    // ── UNIQUE 추가 쪽의 누락 ─────────────────────────────────────────────────

    @Test
    void createUniqueIndexIsAddUnique() {
        // 전에는 CREATE INDEX 계열을 전부 무시해서 ADD_UNIQUE 를 놓쳤다.
        SchemaChange c = only(new SqlMigrationParser().parseSQL("CREATE UNIQUE INDEX uq_e ON users (email, nickname);"));
        assertEquals(ChangeType.ADD_UNIQUE, c.getChangeType());
        assertEquals("users", c.getTableName());
        assertEquals("email", c.getColumnName());
        assertEquals(List.of("email", "nickname"), c.getIndexColumns());
    }

    @Test
    void createPlainIndexIsNotASchemaChange() {
        SqlMigrationParser.ParseResult r = new SqlMigrationParser().parseSQL("CREATE INDEX idx_e ON users (email);");
        assertFalse(r.hasFailures());
        assertTrue(r.changes().isEmpty());
    }

    @Test
    void addUniqueIndexKnowsItsColumn() {
        // 전에는 컬럼을 "unknown" 으로 기록했다.
        SchemaChange c = only(new SqlMigrationParser().parseSQL("ALTER TABLE users ADD UNIQUE INDEX uq_e (email);"));
        assertEquals(ChangeType.ADD_UNIQUE, c.getChangeType());
        assertEquals("email", c.getColumnName());
        assertEquals("uq_e", c.getIndexName());
    }

    // ── 이전 마이그레이션으로 인덱스 정의를 찾는 경우 ─────────────────────────

    @Test
    void namedUniqueConstraintFromHistoryIsDropUnique() {
        SchemaChange c = only(parseWithHistory("ALTER TABLE users DROP INDEX uq_users_nickname;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals(RiskLevel.HIGH, c.getRiskLevel());
        assertEquals("nickname", c.getColumnName());
        assertEquals(List.of("nickname", "team_id"), c.getIndexColumns());
    }

    @Test
    void columnLevelUniqueIsFoundByMysqlImplicitName() {
        // MySQL 은 이름 없는 컬럼 UNIQUE 의 인덱스 이름을 컬럼 이름으로 만든다.
        SchemaChange c = only(parseWithHistory("DROP INDEX email ON users;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("email", c.getColumnName());
    }

    @Test
    void columnLevelUniqueIsFoundByPostgresImplicitName() {
        SchemaChange c = only(parseWithHistory("ALTER TABLE users DROP CONSTRAINT users_email_key;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("email", c.getColumnName());
    }

    @Test
    void createUniqueIndexFromHistoryIsDropUnique() {
        SchemaChange c = only(parseWithHistory("DROP INDEX uq_users_ext ON users;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals(List.of("team_id", "email"), c.getIndexColumns());
    }

    @Test
    void plainIndexFromHistoryIsLowRiskIndexDrop() {
        for (String sql : List.of("DROP INDEX idx_users_team ON users;", "ALTER TABLE users DROP INDEX idx_users_nick;")) {
            SchemaChange c = only(parseWithHistory(sql));
            assertEquals(ChangeType.DROP_INDEX, c.getChangeType(), sql);
            assertEquals(RiskLevel.LOW, c.getRiskLevel(), sql);
            assertNotNull(c.getIndexColumns(), sql);
        }
    }

    @Test
    void postgresDropIndexFindsTableFromHistory() {
        SchemaChange c = only(parseWithHistory("DROP INDEX uq_users_nickname;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("users", c.getTableName());
    }

    @Test
    void dropConstraintThatIsForeignKeyIsDropForeignKey() {
        // PostgreSQL 은 FK 도 DROP CONSTRAINT 로 지운다. 전에는 이 구문도 무시됐다.
        SchemaChange c = only(parseWithHistory("ALTER TABLE users DROP CONSTRAINT fk_users_team;"));
        assertEquals(ChangeType.DROP_FOREIGN_KEY, c.getChangeType());
        assertEquals(RiskLevel.LOW, c.getRiskLevel());
        assertEquals("fk_users_team", c.getFkName());
    }

    @Test
    void dropPrimaryKeyIsDropUnique() {
        SchemaChange c = only(parseWithHistory("ALTER TABLE users DROP PRIMARY KEY;"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("id", c.getColumnName());
    }

    @Test
    void dropUniqueByColumnsIsDropUnique() {
        SchemaChange c = only(new SqlMigrationParser().parseSQL("ALTER TABLE users DROP UNIQUE (email);"));
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("email", c.getColumnName());
    }

    @Test
    void recreatingUniqueOnSameColumnsInSameFileIsCompatible() {
        // 인덱스 이름만 바꾸는 흔한 패턴. 중복을 막는 제약은 계속 있다.
        SqlMigrationParser.ParseResult r = parseWithHistory("""
                ALTER TABLE users DROP INDEX uq_users_nickname;
                ALTER TABLE users ADD CONSTRAINT uq_nickname_team UNIQUE (team_id, nickname);
                """);
        SchemaChange c = only(r);
        assertEquals(ChangeType.MODIFY_COMPATIBLE, c.getChangeType());
        assertEquals(RiskLevel.LOW, c.getRiskLevel());
        assertNotNull(c.getNote());
    }

    @Test
    void recreatingUniqueOnMoreColumnsIsLooserAndStillDropUnique() {
        // (email) → (email, team_id): 팀이 다르면 같은 email 을 허용한다. 기존 의미의 중복을 더 이상 막지 못한다.
        // 같은 컬럼 집합일 때만 상쇄한다.
        SqlMigrationParser.ParseResult r = parseWithHistory("""
                DROP INDEX email ON users;
                CREATE UNIQUE INDEX uq_email_team ON users (email, team_id);
                """);
        assertFalse(r.hasFailures());
        assertTrue(r.changes().stream().anyMatch(c -> c.getChangeType() == ChangeType.DROP_UNIQUE), r.changes().toString());
    }

    @Test
    void indexCreatedEarlierInSameFileIsResolved() {
        SqlMigrationParser.ParseResult r = new SqlMigrationParser(new IndexCatalog()).parseSQL("""
                CREATE INDEX idx_tmp ON users (nickname);
                DROP INDEX idx_tmp ON users;
                """);
        SchemaChange c = only(r);
        assertEquals(ChangeType.DROP_INDEX, c.getChangeType());
        assertEquals(RiskLevel.LOW, c.getRiskLevel());
    }

    @Test
    void historyDropAndRenameAreTracked() {
        IndexCatalog catalog = new IndexCatalog();
        catalog.applyAll(HISTORY);
        catalog.applyAll("""
                ALTER TABLE users DROP INDEX uq_users_ext;
                RENAME TABLE users TO members;
                """);
        assertTrue(catalog.find("users", "uq_users_nickname").isEmpty(), "테이블 이름이 바뀌면 옛 이름으로는 찾지 않는다");
        assertTrue(catalog.find("members", "uq_users_nickname").isPresent());
        assertTrue(catalog.find("members", "uq_users_ext").isEmpty(), "이전 마이그레이션에서 지운 인덱스");
    }

    @Test
    void unparseableHistoryStatementIsCountedNotThrown() {
        IndexCatalog catalog = new IndexCatalog();
        // community V1 처럼 ';' 가 빠져 두 CREATE TABLE 이 한 구문으로 붙은 경우
        catalog.applyAll("CREATE TABLE a (id BIGINT) CREATE TABLE b (id BIGINT); CREATE UNIQUE INDEX uq ON c (x);");
        assertEquals(1, catalog.failedStatements());
        assertTrue(catalog.find("c", "uq").isPresent(), "실패한 구문 뒤의 구문은 계속 반영한다");
    }
}
