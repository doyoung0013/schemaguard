package schemaguard.analyzer;

import schemaguard.model.*;

import java.util.*;

/**
 * SQL만 보고 정한 변경 종류/위험도를 엔티티 매핑(코드가 기대하는 스키마)과 비교해 다시 분류한다.
 *
 * 실제 MySQL 측정(CHANGELOG 9)에서 오탐의 대부분이 "MODIFY 는 전부 HIGH"에서 나왔다.
 * 예) users.nickname 이 이미 @Column(nullable = false, length = 50) 인데 MODIFY nickname VARCHAR(50) NOT NULL
 *     → 코드와 DB가 같아지는 변경일 뿐 깨지는 API는 없다.
 *
 *   비호환 타입 (String 필드인데 INT)          → MODIFY_TYPE        HIGH   (읽기 실패)
 *   길이·범위 축소 (엔티티 500 → DB 200)        → NARROW_COLUMN      MEDIUM (긴 값을 쓰면 실패, 기존 값 잘림)
 *   NOT NULL 추가 + 엔티티는 NULL 을 쓸 수 있음 → ADD_NOT_NULL       MEDIUM (NULL 저장 시 실패)
 *   그 외 호환 (같은 타입, 같거나 큰 길이)      → MODIFY_COMPATIBLE  LOW    (영향 없음)
 *   엔티티가 쓰지 않는 컬럼에 FK 추가           → MODIFY_COMPATIBLE  LOW    (앱은 항상 NULL → FK 위반 없음)
 *   매핑을 모르는 컬럼/타입                    → 그대로 (보수적으로 HIGH)
 *
 * 인덱스 삭제 중 이전 마이그레이션에서 정의를 찾지 못한 것은 엔티티 선언으로 마저 분류한다.
 *   @Table(uniqueConstraints / indexes(unique = true)) 이름이 같음 → DROP_UNIQUE HIGH
 *   @Table(indexes) 이름이 같은 일반 인덱스                         → DROP_INDEX  LOW
 *   이름이 @Column(unique = true) 컬럼 이름과 같음 (MySQL 의 이름)  → DROP_UNIQUE HIGH
 *   그래도 모름                                                    → DROP_UNIQUE HIGH (UNIQUE 일 수 있으므로 보수적으로)
 */
public class ChangeClassifier {

    /** 이전 마이그레이션을 읽지 않았으면(--history 없음) "정의를 찾지 못함" 근거에 그 사실을 덧붙인다. */
    private boolean historyNotRead;

    public ChangeClassifier historyNotRead(boolean v) {
        this.historyNotRead = v;
        return this;
    }

    private static final int JPA_DEFAULT_LENGTH = 255;

    private static final Set<String> STRING_TYPES = Set.of(
            "VARCHAR", "CHAR", "NVARCHAR", "NCHAR", "VARCHAR2", "CHARACTER", "TEXT", "TINYTEXT",
            "MEDIUMTEXT", "LONGTEXT", "CLOB", "CHARACTER VARYING");
    private static final Set<String> UNBOUNDED_STRING = Set.of("TEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB");

    /** 정수형 SQL 타입의 크기(바이트). 작을수록 범위가 좁다. */
    private static final Map<String, Integer> INT_BYTES = Map.of(
            "TINYINT", 1, "SMALLINT", 2, "MEDIUMINT", 3, "INT", 4, "INTEGER", 4, "BIGINT", 8);
    /** Java 정수 타입이 담을 수 있는 크기(바이트). */
    private static final Map<String, Integer> JAVA_INT_BYTES = Map.of(
            "byte", 1, "Byte", 1, "short", 2, "Short", 2, "int", 4, "Integer", 4, "long", 8, "Long", 8);

    public List<SchemaChange> classify(List<SchemaChange> changes,
                                       List<EntityMapping> entities,
                                       List<FkMapping> fkMappings) {
        return classify(changes, entities, fkMappings, List.of());
    }

    /** @param entityIndexes 엔티티 @Table 의 uniqueConstraints / indexes 선언 */
    public List<SchemaChange> classify(List<SchemaChange> changes,
                                       List<EntityMapping> entities,
                                       List<FkMapping> fkMappings,
                                       List<IndexDef> entityIndexes) {
        Map<String, EntityMapping> byColumn = new HashMap<>();
        for (EntityMapping em : entities)
            byColumn.putIfAbsent(em.getTableName() + "." + em.getColumnName(), em);
        Set<String> joinColumns = new HashSet<>();
        Set<String> mappedTables = new HashSet<>();
        for (FkMapping fk : fkMappings) {
            joinColumns.add(fk.getTableName() + "." + fk.getJoinColumnName());
            mappedTables.add(fk.getTableName());
        }
        for (EntityMapping em : entities) mappedTables.add(em.getTableName());

        List<SchemaChange> out = new ArrayList<>();
        for (SchemaChange c : changes) {
            EntityMapping em = c.getColumnName() == null ? null
                    : byColumn.get(c.getTableName() + "." + c.getColumnName());
            switch (c.getChangeType()) {
                case MODIFY_TYPE -> out.addAll(classifyModify(c, em));
                case ADD_NOT_NULL -> out.add(classifyNotNull(c, em));
                case DROP_INDEX -> out.add(c.getIndexColumns() == null
                        ? classifyUnknownIndex(c, entities, entityIndexes) : c);
                case DROP_UNIQUE -> out.add(noteEntityStillUnique(c, entities, entityIndexes));
                case ADD_FOREIGN_KEY -> {
                    String key = c.getTableName() + "." + c.getColumnName();
                    boolean appWrites = byColumn.containsKey(key) || joinColumns.contains(key);
                    // 테이블은 엔티티로 매핑되어 있는데 이 컬럼은 매핑이 없으면, 앱은 이 컬럼에 값을 쓰지 않는다.
                    if (!appWrites && mappedTables.contains(c.getTableName())) {
                        out.add(c.reclassify(ChangeType.MODIFY_COMPATIBLE, RiskLevel.LOW,
                                "엔티티가 " + key + " 에 값을 쓰지 않음 (항상 NULL → FK 위반 없음)"));
                    } else {
                        out.add(c);
                    }
                }
                default -> out.add(c);
            }
        }
        return out;
    }

    private List<SchemaChange> classifyModify(SchemaChange c, EntityMapping em) {
        SchemaChange.ColumnDef def = c.getColumnDef();
        if (def == null || em == null || em.getJavaType() == null) return List.of(c);   // 모름 → 보수적으로 유지

        String java = em.getJavaType();
        String sql = def.dataType();
        List<SchemaChange> out = new ArrayList<>();

        if (isStringJava(java)) {
            if (!STRING_TYPES.contains(sql)) {
                return List.of(c.reclassify(ChangeType.MODIFY_TYPE, RiskLevel.HIGH,
                        "Java " + java + " 필드인데 컬럼이 " + def + " 로 바뀜 (비호환)"));
            }
            int expected = em.getLength() != null ? em.getLength() : JPA_DEFAULT_LENGTH;
            Integer newLen = UNBOUNDED_STRING.contains(sql) ? null : def.firstArgAsInt();
            if (newLen != null && newLen < expected) {
                out.add(c.reclassify(ChangeType.NARROW_COLUMN, RiskLevel.MEDIUM,
                        "엔티티 길이 " + expected + (em.getLength() == null ? "(JPA 기본값)" : "")
                                + " → " + def + ": " + (newLen + 1) + "~" + expected
                                + "자 값은 저장 실패(strict 모드) 또는 잘림. 기존 데이터 길이 확인 필요"));
            }
        } else if (JAVA_INT_BYTES.containsKey(java)) {
            Integer newBytes = INT_BYTES.get(sql);
            if (newBytes == null) {
                if (!Set.of("DECIMAL", "NUMERIC").contains(sql))
                    return List.of(c.reclassify(ChangeType.MODIFY_TYPE, RiskLevel.HIGH,
                            "Java " + java + " 필드인데 컬럼이 " + def + " 로 바뀜 (비호환)"));
            } else if (newBytes < JAVA_INT_BYTES.get(java)) {
                out.add(c.reclassify(ChangeType.NARROW_COLUMN, RiskLevel.MEDIUM,
                        "Java " + java + " 범위보다 좁은 " + def + ": 큰 값 저장 실패 가능. 기존 데이터 범위 확인 필요"));
            }
        } else {
            return List.of(c);   // 날짜/불리언/소수 등은 아직 판단하지 않는다 (보수적으로 유지)
        }

        if (def.notNull() && em.isNullable()) {
            out.add(c.reclassify(ChangeType.ADD_NOT_NULL, RiskLevel.MEDIUM,
                    "NOT NULL 추가, 엔티티 필드는 NULL 허용: NULL 저장 시 실패. 기존 NULL 데이터 확인 필요"));
        }
        if (out.isEmpty()) {
            out.add(c.reclassify(ChangeType.MODIFY_COMPATIBLE, RiskLevel.LOW,
                    "엔티티 매핑(" + java + (em.getLength() != null ? ", length " + em.getLength() : "")
                            + (em.isNullable() ? "" : ", nullable=false") + ")과 호환되는 " + def));
        }
        return out;
    }

    private SchemaChange classifyNotNull(SchemaChange c, EntityMapping em) {
        if (em != null && !em.isNullable()) {
            return c.reclassify(ChangeType.MODIFY_COMPATIBLE, RiskLevel.LOW,
                    "엔티티가 이미 NULL 을 쓰지 않음 (nullable=false/기본형/@Id)");
        }
        return c;
    }

    private SchemaChange classifyUnknownIndex(SchemaChange c, List<EntityMapping> entities, List<IndexDef> entityIndexes) {
        String table = c.getTableName();
        String name = c.getIndexName();

        Optional<IndexDef> declared = entityIndexes.stream()
                .filter(d -> table.equals(d.table()) && d.answersTo(name)).findFirst();
        if (declared.isPresent()) {
            IndexDef d = declared.get();
            String cols = String.join(", ", d.columns());
            return d.enforcesUniqueness()
                    ? noteEntityStillUnique(c.resolvedIndex(table, d.columns(), ChangeType.DROP_UNIQUE, RiskLevel.HIGH,
                            "UNIQUE (" + cols + ") 삭제 (정의: " + d.origin() + "): " + DUPLICATES), entities, entityIndexes)
                    : c.resolvedIndex(table, d.columns(), ChangeType.DROP_INDEX, RiskLevel.LOW,
                            "일반 인덱스 (" + cols + ") 삭제 (정의: " + d.origin() + "): 쿼리는 깨지지 않고 조회 성능만 영향");
        }

        Optional<EntityMapping> uniqueColumn = entities.stream()
                .filter(m -> m.isUnique() && table.equals(m.getTableName()) && m.getColumnName().equalsIgnoreCase(name))
                .findFirst();
        if (uniqueColumn.isPresent()) {
            EntityMapping m = uniqueColumn.get();
            return c.resolvedIndex(table, List.of(m.getColumnName()), ChangeType.DROP_UNIQUE, RiskLevel.HIGH,
                    "UNIQUE (" + m.getColumnName() + ") 삭제: 엔티티 " + m.getEntityClass() + "." + m.getFieldName()
                            + " 가 @Column(unique = true) 이고 MySQL 은 이 인덱스 이름을 컬럼 이름으로 만든다. " + DUPLICATES);
        }

        return c.reclassify(ChangeType.DROP_UNIQUE, RiskLevel.HIGH,
                "인덱스 " + name + " 의 정의를 이전 마이그레이션과 엔티티에서 찾지 못함"
                        + (historyNotRead ? " (이전 마이그레이션은 --history 를 줄 때만 읽음)" : "")
                        + ": UNIQUE 인지 알 수 없어 보수적으로 UNIQUE 로 간주. 일반 인덱스라면 이 경고는 무시해도 됨");
    }

    /** 엔티티가 여전히 같은 컬럼을 unique 로 선언하면 코드와 DB 가 어긋난다는 것을 근거에 덧붙인다. */
    private static SchemaChange noteEntityStillUnique(SchemaChange c, List<EntityMapping> entities, List<IndexDef> entityIndexes) {
        List<String> cols = c.getIndexColumns();
        if (cols == null || cols.isEmpty()) return c;
        String table = c.getTableName();
        IndexDef dropped = new IndexDef(table, null, null, cols, IndexDef.Kind.UNIQUE, null);

        List<String> still = new ArrayList<>();
        if (cols.size() == 1) {
            entities.stream()
                    .filter(m -> m.isUnique() && table.equals(m.getTableName()) && m.getColumnName().equalsIgnoreCase(cols.get(0)))
                    .forEach(m -> still.add(m.getEntityClass() + "." + m.getFieldName() + " @Column(unique = true)"));
        }
        entityIndexes.stream()
                .filter(d -> d.enforcesUniqueness() && table.equals(d.table()) && dropped.sameColumnsAs(d.columns()))
                .forEach(d -> still.add(d.origin()));
        if (still.isEmpty()) return c;
        String base = c.getNote() == null ? "" : c.getNote() + ". ";
        return c.reclassify(c.getChangeType(), c.getRiskLevel(),
                base + "엔티티 " + String.join(", ", still) + " 는 아직 unique 로 선언되어 있어 코드와 DB 가 어긋남");
    }

    private static final String DUPLICATES = "같은 값을 저장해도 오류 없이 성공함. 이 제약으로 중복을 막던 저장 경로 확인 필요";

    private static boolean isStringJava(String java) {
        return java.equals("String") || java.equals("char") || java.equals("Character");
    }
}
