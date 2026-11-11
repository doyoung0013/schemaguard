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
 */
public class ChangeClassifier {

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

    private static boolean isStringJava(String java) {
        return java.equals("String") || java.equals("char") || java.equals("Character");
    }
}
