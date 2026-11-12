package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import schemaguard.model.*;
import schemaguard.parser.EntityParser;
import schemaguard.parser.SqlMigrationParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 엔티티 매핑과 비교한 변경 재분류. 실제 MySQL 측정(CHANGELOG 9)에서 오탐 50건의 원인이던
 * "MODIFY 는 전부 HIGH", "FK 제약 변경도 HIGH" 를 고친 규칙을 고정한다.
 */
class ChangeClassifierTest {

    private static final String USER = """
            @Entity @Table(name = "users")
            public class User {
                @Id private Long id;
                @Column(nullable = false, length = 50) private String nickname;
                @Column(length = 500) private String bio;
                private String memo;
                private Integer age;
                private int score;
                @Enumerated(EnumType.STRING) private Role role;
                @ManyToOne @JoinColumn(name = "team_id") private Team team;
            }
            """;

    private static List<SchemaChange> classify(String sql) throws Exception {
        EntityParser.ParseResult pr = new EntityParser().parseSource(USER);
        List<SchemaChange> changes = new SqlMigrationParser().parseSQL(sql).changes();
        return new ChangeClassifier().classify(changes, pr.entityMappings(), pr.fkMappings());
    }

    private static SchemaChange one(String sql) throws Exception {
        List<SchemaChange> r = classify(sql);
        assertEquals(1, r.size(), r.toString());
        return r.get(0);
    }

    @Test
    void sameDefinitionAsEntityIsCompatible() throws Exception {
        // community V5: 엔티티가 이미 nullable=false, length=50
        SchemaChange c = one("ALTER TABLE users MODIFY nickname VARCHAR(50) NOT NULL;");
        assertEquals(ChangeType.MODIFY_COMPATIBLE, c.getChangeType());
        assertEquals(RiskLevel.LOW, c.getRiskLevel());
    }

    @Test
    void wideningIsCompatible() throws Exception {
        assertEquals(ChangeType.MODIFY_COMPATIBLE, one("ALTER TABLE users MODIFY bio TEXT;").getChangeType());
        assertEquals(ChangeType.MODIFY_COMPATIBLE, one("ALTER TABLE users MODIFY age BIGINT;").getChangeType());
    }

    @Test
    void shrinkingBelowEntityLengthIsNarrowMedium() throws Exception {
        // community V16: 엔티티 length 500 → VARCHAR(200)
        SchemaChange c = one("ALTER TABLE users MODIFY bio VARCHAR(200);");
        assertEquals(ChangeType.NARROW_COLUMN, c.getChangeType());
        assertEquals(RiskLevel.MEDIUM, c.getRiskLevel());
        assertTrue(c.getNote().contains("201~500"), c.getNote());
    }

    @Test
    void lengthDefaultsToJpa255WhenNotDeclared() throws Exception {
        assertEquals(ChangeType.NARROW_COLUMN, one("ALTER TABLE users MODIFY memo VARCHAR(100);").getChangeType());
        assertEquals(ChangeType.MODIFY_COMPATIBLE, one("ALTER TABLE users MODIFY memo VARCHAR(255);").getChangeType());
    }

    @Test
    void numericRangeNarrowing() throws Exception {
        assertEquals(ChangeType.NARROW_COLUMN, one("ALTER TABLE users MODIFY age SMALLINT;").getChangeType());
        assertEquals(ChangeType.NARROW_COLUMN, one("ALTER TABLE users MODIFY score TINYINT;").getChangeType());
    }

    @Test
    void incompatibleTypeStaysHigh() throws Exception {
        SchemaChange c = one("ALTER TABLE users MODIFY memo INT;");
        assertEquals(ChangeType.MODIFY_TYPE, c.getChangeType());
        assertEquals(RiskLevel.HIGH, c.getRiskLevel());
        assertEquals(ChangeType.MODIFY_TYPE, one("ALTER TABLE users MODIFY age VARCHAR(10);").getChangeType());
    }

    @Test
    void enumStringIsTreatedAsString() throws Exception {
        assertEquals(ChangeType.MODIFY_COMPATIBLE, one("ALTER TABLE users MODIFY role VARCHAR(255);").getChangeType());
        assertEquals(ChangeType.MODIFY_TYPE, one("ALTER TABLE users MODIFY role INT;").getChangeType());
    }

    @Test
    void notNullOnNullableFieldIsSeparateWriteRisk() throws Exception {
        List<SchemaChange> r = classify("ALTER TABLE users MODIFY bio VARCHAR(100) NOT NULL;");
        assertEquals(List.of(ChangeType.NARROW_COLUMN, ChangeType.ADD_NOT_NULL),
                r.stream().map(SchemaChange::getChangeType).toList());
    }

    @Test
    void setNotNullOnFieldThatNeverWritesNullIsCompatible() throws Exception {
        assertEquals(ChangeType.MODIFY_COMPATIBLE,
                one("ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;").getChangeType());
        assertEquals(ChangeType.ADD_NOT_NULL,
                one("ALTER TABLE users ALTER COLUMN memo SET NOT NULL;").getChangeType());
    }

    @Test
    void unknownColumnOrTypeIsKeptConservative() throws Exception {
        // 엔티티에 없는 컬럼 (community V4: users.age 가 엔티티에 없음)
        assertEquals(ChangeType.MODIFY_TYPE, one("ALTER TABLE users MODIFY ghost VARCHAR(10);").getChangeType());
    }

    @Test
    void foreignKeyOnColumnTheEntityNeverWritesHasNoImpact() throws Exception {
        // community V10: 엔티티가 모르는 reviewer_id 에 FK 추가
        SchemaChange unmapped = one("ALTER TABLE users ADD CONSTRAINT fk_x FOREIGN KEY (reviewer_id) REFERENCES users(id);");
        assertEquals(ChangeType.MODIFY_COMPATIBLE, unmapped.getChangeType());
        // 엔티티가 쓰는 join 컬럼이면 유지 (쓰기 경로 MEDIUM)
        SchemaChange mapped = one("ALTER TABLE users ADD CONSTRAINT fk_t FOREIGN KEY (team_id) REFERENCES teams(id);");
        assertEquals(ChangeType.ADD_FOREIGN_KEY, mapped.getChangeType());
    }

    @Test
    void foreignKeyConstraintChangesAreNotHighAnymore() throws Exception {
        assertEquals(RiskLevel.LOW, one("ALTER TABLE users DROP FOREIGN KEY fk_t;").getRiskLevel());
        List<SchemaChange> r = classify("""
                ALTER TABLE users DROP FOREIGN KEY fk_t;
                ALTER TABLE users ADD CONSTRAINT fk_t2 FOREIGN KEY (team_id) REFERENCES orgs(id);
                """);
        assertEquals(ChangeType.MODIFY_FK_REFERENCE, r.get(0).getChangeType());
        assertEquals(RiskLevel.MEDIUM, r.get(0).getRiskLevel());
    }
}
