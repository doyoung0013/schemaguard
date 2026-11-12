package schemaguard.parser;

import org.junit.jupiter.api.Test;
import schemaguard.analyzer.LockScopeAnalyzer;
import schemaguard.parser.DerivedQuery.Condition;
import schemaguard.parser.DerivedQuery.Seek;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DerivedQueryTest {

    private static List<List<Condition>> parse(String name) {
        return DerivedQuery.parse(name).orElseThrow(() -> new AssertionError("not a derived query: " + name));
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
    void equalityAndConjunction() {
        assertEquals("productId = EQUALITY & status = EQUALITY", show(parse("findByProductIdAndStatus")));
        assertEquals("id = EQUALITY", show(parse("findById")));
        assertEquals("email = EQUALITY", show(parse("findFirstByEmail")));
        assertEquals("code = EQUALITY", show(parse("findDistinctTop3ByCode")));
        assertEquals("userId = EQUALITY", show(parse("existsByUserId")));
    }

    @Test
    void orMakesSeparateBranches() {
        assertEquals("email = EQUALITY | nickname = EQUALITY", show(parse("findByEmailOrNickname")));
    }

    @Test
    void keywordsInsidePropertyNamesAreNotSplit() {
        // Order, Android, Brand 은 Or/And 키워드가 아니다
        assertEquals("orderNo = EQUALITY & androidId = EQUALITY", show(parse("findByOrderNoAndAndroidId")));
        assertEquals("brandName = EQUALITY", show(parse("findByBrandName")));
        assertEquals("status = EQUALITY", show(parse("findByStatus")));     // "Status" 끝의 s 를 연산자로 보지 않는다
    }

    @Test
    void orderByIsSortingNotACondition() {
        assertEquals("status = EQUALITY", show(parse("findByStatusOrderByIdDesc")));
        assertEquals("", show(parse("findAllByOrderByIdDesc")));
    }

    @Test
    void operatorsDecideWhetherAnIndexCanSeek() {
        assertEquals("productId In EQUALITY", show(parse("findByProductIdIn")));
        assertEquals("deletedAt IsNull EQUALITY", show(parse("findByDeletedAtIsNull")));
        assertEquals("active True EQUALITY", show(parse("findByActiveTrue")));
        assertEquals("createdAt Between RANGE", show(parse("findByCreatedAtBetween")));
        assertEquals("price GreaterThanEqual RANGE", show(parse("findByPriceGreaterThanEqual")));
        assertEquals("name StartingWith RANGE", show(parse("findByNameStartingWith")));
        assertEquals("name Containing NONE", show(parse("findByNameContaining")));
        assertEquals("name Like NONE", show(parse("findByNameLike")));       // 패턴을 모른다 (앞이 % 일 수 있다)
        assertEquals("status Not NONE", show(parse("findByStatusNot")));
        assertEquals("status NotIn NONE", show(parse("findByStatusNotIn")));
        assertEquals("deletedAt IsNotNull NONE", show(parse("findByDeletedAtIsNotNull")));
    }

    @Test
    void ignoreCaseWrapsTheColumnInAFunction() {
        assertEquals("email = NONE", show(parse("findByEmailIgnoreCase")));
        assertEquals("email = NONE & name = NONE", show(parse("findByEmailAndNameAllIgnoreCase")));
    }

    @Test
    void nonDerivedNamesAreNotParsed() {
        assertTrue(DerivedQuery.parse("findAll").isEmpty());
        assertTrue(DerivedQuery.parse("save").isEmpty());
        assertTrue(DerivedQuery.parse("lockForUpdate").isEmpty());
    }

    private static Map<String, Seek> cols(Object... kv) {
        Map<String, Seek> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Seek) kv[i + 1]);
        return m;
    }

    @Test
    void leftmostPrefix() {
        Map<String, Seek> eq2 = cols("product_id", Seek.EQUALITY, "status", Seek.EQUALITY);
        assertEquals(2, LockScopeAnalyzer.prefix(List.of("product_id", "status"), eq2));
        assertEquals(2, LockScopeAnalyzer.prefix(List.of("status", "product_id", "quantity"), eq2));
        assertEquals(1, LockScopeAnalyzer.prefix(List.of("product_id", "quantity", "status"), eq2));  // 가운데가 끊긴다
        assertEquals(0, LockScopeAnalyzer.prefix(List.of("quantity", "product_id"), eq2));            // 맨 앞이 조건이 아니다

        // 범위 조건은 그 컬럼까지만 쓴다
        Map<String, Seek> range = cols("created_at", Seek.RANGE, "status", Seek.EQUALITY);
        assertEquals(1, LockScopeAnalyzer.prefix(List.of("created_at", "status"), range));
        assertEquals(2, LockScopeAnalyzer.prefix(List.of("status", "created_at"), range));

        // 인덱스를 못 쓰는 조건(NOT, LIKE …)은 맞지 않은 것과 같다
        assertEquals(0, LockScopeAnalyzer.prefix(List.of("name"), cols("name", Seek.NONE)));
    }
}
