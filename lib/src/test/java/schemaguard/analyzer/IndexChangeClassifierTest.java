package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import schemaguard.model.*;
import schemaguard.parser.EntityParser;
import schemaguard.parser.MigrationParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이전 마이그레이션에서 정의를 찾지 못한 인덱스 삭제를 엔티티 선언으로 마저 분류한다.
 * 엔티티에도 없으면 UNIQUE 인지 알 수 없으므로 보수적으로 UNIQUE 로 본다.
 */
class IndexChangeClassifierTest {

    private static final String ENTITIES = """
            @Entity
            @Table(name = "post_likes",
                   uniqueConstraints = {
                       @UniqueConstraint(name = "uk_post_like_post_user", columnNames = {"post_id", "user_id"})
                   },
                   indexes = {
                       @Index(name = "idx_post_like_created", columnList = "created_at"),
                       @Index(name = "uq_post_like_ext", columnList = "ext_id, user_id", unique = true)
                   })
            public class PostLike {
                @Id private Long id;
                @Column(name = "post_id") private Long postId;
                @Column(name = "user_id") private Long userId;
                @Column(name = "ext_id") private String extId;
                @Column(name = "created_at") private java.time.LocalDateTime createdAt;
            }
            @Entity @Table(name = "users")
            public class User {
                @Id private Long id;
                @Column(nullable = false, unique = true, length = 100) private String email;
                private String nickname;
            }
            """;

    private static SchemaChange classifyOne(String sql) throws Exception {
        EntityParser.ParseResult pr = new EntityParser().parseSource(ENTITIES);
        MigrationParser.ParseResult parsed = new MigrationParser().parseSQL(sql);
        assertFalse(parsed.hasFailures(), () -> parsed.failures().toString());
        List<SchemaChange> out = new ChangeClassifier()
                .classify(parsed.changes(), pr.entityMappings(), pr.fkMappings(), pr.indexes());
        assertEquals(1, out.size(), out::toString);
        return out.get(0);
    }

    @Test
    void entityParserReadsUniqueDeclarations() throws Exception {
        EntityParser.ParseResult pr = new EntityParser().parseSource(ENTITIES);
        assertTrue(pr.entityMappings().stream()
                .anyMatch(m -> m.getColumnName().equals("email") && m.isUnique()));
        assertTrue(pr.entityMappings().stream()
                .anyMatch(m -> m.getColumnName().equals("nickname") && !m.isUnique()));
        IndexDef uk = pr.indexes().stream().filter(i -> "uk_post_like_post_user".equals(i.name())).findFirst().orElseThrow();
        assertEquals("post_likes", uk.table());
        assertEquals(List.of("post_id", "user_id"), uk.columns());
        assertTrue(uk.enforcesUniqueness());
        IndexDef plain = pr.indexes().stream().filter(i -> "idx_post_like_created".equals(i.name())).findFirst().orElseThrow();
        assertFalse(plain.enforcesUniqueness());
        IndexDef uniqueIndex = pr.indexes().stream().filter(i -> "uq_post_like_ext".equals(i.name())).findFirst().orElseThrow();
        assertTrue(uniqueIndex.enforcesUniqueness());
        assertEquals(List.of("ext_id", "user_id"), uniqueIndex.columns());
    }

    @Test
    void uniqueConstraintDeclaredOnEntityIsDropUnique() throws Exception {
        SchemaChange c = classifyOne("ALTER TABLE post_likes DROP INDEX uk_post_like_post_user;");
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals(RiskLevel.HIGH, c.getRiskLevel());
        assertEquals("post_id", c.getColumnName());
        assertEquals(List.of("post_id", "user_id"), c.getIndexColumns());
        assertTrue(c.getNote().contains("PostLike"), c.getNote());
    }

    @Test
    void uniqueIndexDeclaredOnEntityIsDropUnique() throws Exception {
        SchemaChange c = classifyOne("DROP INDEX uq_post_like_ext ON post_likes;");
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
    }

    @Test
    void plainIndexDeclaredOnEntityIsLowRisk() throws Exception {
        SchemaChange c = classifyOne("DROP INDEX idx_post_like_created ON post_likes;");
        assertEquals(ChangeType.DROP_INDEX, c.getChangeType());
        assertEquals(RiskLevel.LOW, c.getRiskLevel());
    }

    @Test
    void indexNamedAfterUniqueColumnIsDropUnique() throws Exception {
        // @Column(unique = true) 를 MySQL 에 만들면 인덱스 이름이 컬럼 이름이 된다.
        SchemaChange c = classifyOne("DROP INDEX email ON users;");
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals("email", c.getColumnName());
    }

    @Test
    void unknownIndexIsConservativelyUnique() throws Exception {
        SchemaChange c = classifyOne("ALTER TABLE users DROP INDEX uq_somewhere;");
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertEquals(RiskLevel.HIGH, c.getRiskLevel());
        assertNotNull(c.getNote());
        assertTrue(c.getNote().contains("보수적"), c.getNote());
    }

    @Test
    void resolvedDropUniqueNotesThatEntityStillDeclaresIt() throws Exception {
        // 이전 마이그레이션에서 이미 UNIQUE 로 확인된 경우에도, 엔티티가 여전히 unique 로 선언하면 코드와 DB 가 어긋난다.
        EntityParser.ParseResult pr = new EntityParser().parseSource(ENTITIES);
        schemaguard.parser.IndexCatalog catalog = new schemaguard.parser.IndexCatalog();
        catalog.applyAll("CREATE TABLE users (id BIGINT PRIMARY KEY, email VARCHAR(100) UNIQUE);");
        List<SchemaChange> parsed = new MigrationParser(catalog).parseSQL("DROP INDEX email ON users;").changes();
        SchemaChange c = new ChangeClassifier()
                .classify(parsed, pr.entityMappings(), pr.fkMappings(), pr.indexes()).get(0);
        assertEquals(ChangeType.DROP_UNIQUE, c.getChangeType());
        assertTrue(c.getNote().contains("User.email"), c.getNote());
    }
}
