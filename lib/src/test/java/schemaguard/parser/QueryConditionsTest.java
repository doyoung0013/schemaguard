package schemaguard.parser;

import org.junit.jupiter.api.Test;
import schemaguard.parser.DerivedQuery.Condition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryConditionsTest {

    private static QueryConditions.Parsed ok(String q, boolean nativeSql) {
        QueryConditions.Result r = QueryConditions.parse(q, nativeSql);
        assertNull(r.error(), () -> "unexpected error: " + r.error());
        return r.parsed();
    }

    private static String show(List<List<Condition>> branches) {
        StringBuilder sb = new StringBuilder();
        for (List<Condition> b : branches) {
            if (!sb.isEmpty()) sb.append(" | ");
            for (int i = 0; i < b.size(); i++) {
                Condition c = b.get(i);
                if (i > 0) sb.append(" & ");
                sb.append(c.property()).append(' ').append(c.operator()).append(' ').append(c.seek());
            }
        }
        return sb.toString();
    }

    @Test
    void jpqlRootAliasAndConditions() {
        QueryConditions.Parsed p = ok("select s from Stock s where s.product.id = :productId and s.status = :status", false);
        assertEquals("Stock", p.root());
        assertEquals("s", p.rootAlias());
        assertFalse(p.nativeSql());
        assertEquals("s.product.id = EQUALITY & s.status = EQUALITY", show(p.branches()));
    }

    @Test
    void jpqlJoinAliasesAreRecordedAsPathsFromTheRoot() {
        QueryConditions.Parsed p = ok("select s from com.shop.Stock as s join fetch s.product p left join p.maker m "
                + "where p.id = :id order by s.id", false);
        assertEquals("Stock", p.root());
        assertEquals(Map.of("p", "product", "m", "product.maker"), p.joinAliases());
        assertEquals("p.id = EQUALITY", show(p.branches()));
    }

    @Test
    void orIsExpandedIntoBranches() {
        QueryConditions.Parsed p = ok("select s from Stock s where s.product.id = :p and (s.status = 'A' or s.status = 'B')", false);
        assertEquals("s.product.id = EQUALITY & s.status = EQUALITY | s.product.id = EQUALITY & s.status = EQUALITY",
                show(p.branches()));
    }

    @Test
    void operatorsDecideWhetherAnIndexCanSeek() {
        assertEquals("s.id IN EQUALITY", show(ok("select s from Stock s where s.id in :ids", false).branches()));
        assertEquals("s.createdAt BETWEEN RANGE & s.deletedAt IS NULL EQUALITY",
                show(ok("select s from Stock s where s.createdAt between :a and :b and s.deletedAt is null", false).branches()));
        assertEquals("s.qty > RANGE", show(ok("select s from Stock s where s.qty > 0", false).branches()));
        assertEquals("s.qty < RANGE", show(ok("select s from Stock s where 0 < s.qty", false).branches()));   // 컬럼이 오른쪽(연산자는 쓴 그대로)
        assertEquals("s.name LIKE 'x%' RANGE", show(ok("select s from Stock s where s.name like 'abc%'", false).branches()));
        assertEquals("s.name LIKE NONE", show(ok("select s from Stock s where s.name like :pattern", false).branches()));
        assertEquals("s.name LIKE NONE", show(ok("select s from Stock s where s.name like '%abc'", false).branches()));
        assertEquals("s.status <> NONE", show(ok("select s from Stock s where s.status <> 'X'", false).branches()));
        assertEquals("s.status NOT IN NONE", show(ok("select s from Stock s where s.status not in :xs", false).branches()));
        assertEquals("s.deletedAt IS NOT NULL NONE", show(ok("select s from Stock s where s.deletedAt is not null", false).branches()));
        assertEquals("s.active NOT NONE", show(ok("select s from Stock s where not s.active = true", false).branches()));
        // 함수로 감싼 컬럼은 인덱스를 쓰지 못한다
        assertEquals("s.email = NONE", show(ok("select s from Stock s where upper(s.email) = upper(:e)", false).branches()));
        // 컬럼끼리 비교(조인 조건)
        assertEquals("s.a = NONE & s.b = NONE", show(ok("select s from Stock s where s.a = s.b", false).branches()));
    }

    @Test
    void booleanLiteralIsNotAColumn() {
        assertEquals("s.active = EQUALITY", show(ok("select s from Stock s where s.active = true", false).branches()));
    }

    @Test
    void nativeQueryWithForUpdate() {
        QueryConditions.Parsed p = ok("SELECT * FROM stocks s WHERE s.product_id = ?1 AND status = ?2 FOR UPDATE", true);
        assertTrue(p.nativeSql());
        assertTrue(p.lockClause());
        assertEquals("stocks", p.root());
        assertEquals("s", p.rootAlias());
        assertEquals("s.product_id = EQUALITY & status = EQUALITY", show(p.branches()));
        assertTrue(ok("select * from stocks where id = :id lock in share mode", true).lockClause());
        assertTrue(ok("select * from stocks where id = :id for share", true).lockClause());
        assertFalse(ok("select * from stocks where id = :id", true).lockClause());
    }

    @Test
    void nativeJoinAliasesPointToOtherTables() {
        QueryConditions.Parsed p = ok("select s.* from stocks s join products p on p.id = s.product_id where p.name = :n for update", true);
        assertTrue(p.joinAliases().containsKey("p"));
        assertNull(p.joinAliases().get("p"));
    }

    @Test
    void noWhereMeansNoConditions() {
        assertEquals(List.of(), ok("select s from Stock s", false).branches());
    }

    @Test
    void unreadableWhereIsAnErrorNotAPartialResult() {
        // JSQLParser 가 앞부분(s.tags)만 읽고 뒤를 버리면 틀린 판정이 된다 → 실패로 돌려준다
        QueryConditions.Result r = QueryConditions.parse("select s from Stock s where s.tags is empty", false);
        assertNotNull(r.error());
        assertNull(r.parsed());
        assertNotNull(QueryConditions.parse("select s", false).error());
    }

    @Test
    void tooManyOrBranchesIsAnError() {
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) w.append(" and ");
            w.append("(s.a").append(i).append(" = 1 or s.b").append(i).append(" = 2)");   // 2^6 = 64 갈래
        }
        assertNotNull(QueryConditions.parse("select s from Stock s where " + w, false).error());
    }
}
