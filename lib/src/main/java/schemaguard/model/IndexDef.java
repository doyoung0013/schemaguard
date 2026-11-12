package schemaguard.model;

import java.util.List;
import java.util.Locale;

/**
 * 테이블의 인덱스 또는 제약 하나. 이전 마이그레이션(IndexCatalog)이나 엔티티 선언(@Table, @Column)에서 만든다.
 *
 * @param table   테이블 이름 (소문자)
 * @param name    인덱스·제약 이름 (소문자). 이름 없이 선언된 것은 null
 * @param aliases DB 가 이름 없는 제약에 붙이는 이름 후보. 예) MySQL 컬럼 UNIQUE → "email", PostgreSQL → "users_email_key"
 * @param columns 대상 컬럼 (선언 순서)
 * @param kind    종류
 * @param origin  어디서 알았는지. 예) "V1__init.sql", "PostLike (@Table uniqueConstraints)"
 */
public record IndexDef(String table, String name, List<String> aliases, List<String> columns, Kind kind, String origin) {

    public enum Kind { PRIMARY_KEY, UNIQUE, INDEX, FOREIGN_KEY }

    public IndexDef {
        table = lower(table);
        name = lower(name);
        aliases = aliases == null ? List.of() : aliases.stream().map(IndexDef::lower).toList();
        columns = columns == null ? List.of() : columns.stream().map(IndexDef::lower).toList();
    }

    /** 이 이름으로 DROP 할 수 있는 인덱스인지 (이름 또는 DB 가 붙인 이름 후보). */
    public boolean answersTo(String indexName) {
        String n = lower(indexName);
        return n != null && (n.equals(name) || aliases.contains(n));
    }

    /** 이 인덱스가 없어지면 중복 저장을 DB 가 더 이상 막지 않는다. */
    public boolean enforcesUniqueness() {
        return kind == Kind.PRIMARY_KEY || kind == Kind.UNIQUE;
    }

    /** 같은 컬럼 집합인지 (순서 무관). UNIQUE 의 의미는 컬럼 순서와 관계없다. */
    public boolean sameColumnsAs(List<String> other) {
        return other != null && columns.size() == other.size()
                && columns.containsAll(other.stream().map(IndexDef::lower).toList());
    }

    public IndexDef onTable(String newTable) {
        return new IndexDef(newTable, name, aliases, columns, kind, origin);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
