package schemaguard.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Data 파생 쿼리 메서드 이름을 조회 조건으로 나눈다.
 *
 *   findByProductIdAndStatus          → [[productId =, status =]]
 *   findFirstByEmailOrNickname        → [[email =], [nickname =]]          (Or 는 따로 찾는 갈래)
 *   findByCreatedAtBetweenOrderByIdDesc → [[createdAt BETWEEN]]           (OrderBy 뒤는 정렬이라 버린다)
 *
 * 속성 이름을 컬럼으로 바꾸는 일은 엔티티 매핑을 아는 쪽(LockScopeAnalyzer)이 한다.
 * 연산자마다 그 조건으로 인덱스를 탈 수 있는지(index seek) 표시한다. MySQL B-tree 기준이다.
 *   =, IN, IS NULL, TRUE/FALSE                 → EQUALITY (복합 인덱스의 다음 컬럼도 쓸 수 있다)
 *   <, <=, >, >=, BETWEEN, BEFORE/AFTER, LIKE 'x%' → RANGE (이 컬럼까지만 쓸 수 있다)
 *   NOT, NOT IN, LIKE(패턴 모름), %x%, %x, IgnoreCase(UPPER(col)), IS NOT NULL, 컬렉션 연산 → NONE
 */
public final class DerivedQuery {

    public enum Seek { EQUALITY, RANGE, NONE }

    public record Condition(String property, String operator, Seek seek) {}

    private DerivedQuery() {}

    private static final Pattern PREFIX = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)(.*?)By(.*)$");

    /**
     * 연산자 접미사. 긴 것부터 맞춘다(IsNotNull 이 Null 보다 먼저).
     * 값이 EQUALITY/RANGE/NONE.
     */
    private static final Object[][] OPERATORS = {
            {"IsNotNull", Seek.NONE}, {"NotNull", Seek.NONE},
            {"IsNull", Seek.EQUALITY}, {"Null", Seek.EQUALITY},
            {"IsNotIn", Seek.NONE}, {"NotIn", Seek.NONE},
            {"IsNotLike", Seek.NONE}, {"NotLike", Seek.NONE},
            {"IsNotContaining", Seek.NONE}, {"NotContaining", Seek.NONE}, {"NotContains", Seek.NONE},
            {"IsNotEmpty", Seek.NONE}, {"NotEmpty", Seek.NONE}, {"IsEmpty", Seek.NONE}, {"Empty", Seek.NONE},
            {"IsLessThanEqual", Seek.RANGE}, {"LessThanEqual", Seek.RANGE},
            {"IsGreaterThanEqual", Seek.RANGE}, {"GreaterThanEqual", Seek.RANGE},
            {"IsLessThan", Seek.RANGE}, {"LessThan", Seek.RANGE},
            {"IsGreaterThan", Seek.RANGE}, {"GreaterThan", Seek.RANGE},
            {"IsBetween", Seek.RANGE}, {"Between", Seek.RANGE},
            {"IsBefore", Seek.RANGE}, {"Before", Seek.RANGE},
            {"IsAfter", Seek.RANGE}, {"After", Seek.RANGE},
            {"IsStartingWith", Seek.RANGE}, {"StartingWith", Seek.RANGE}, {"StartsWith", Seek.RANGE},
            {"IsEndingWith", Seek.NONE}, {"EndingWith", Seek.NONE}, {"EndsWith", Seek.NONE},
            {"IsContaining", Seek.NONE}, {"Containing", Seek.NONE}, {"Contains", Seek.NONE},
            {"IsLike", Seek.NONE}, {"Like", Seek.NONE},
            {"MatchesRegex", Seek.NONE}, {"Matches", Seek.NONE}, {"Regex", Seek.NONE},
            {"IsTrue", Seek.EQUALITY}, {"True", Seek.EQUALITY},
            {"IsFalse", Seek.EQUALITY}, {"False", Seek.EQUALITY},
            {"IsIn", Seek.EQUALITY}, {"In", Seek.EQUALITY},
            {"IsNot", Seek.NONE}, {"Not", Seek.NONE},
            {"Equals", Seek.EQUALITY}, {"Is", Seek.EQUALITY},
            {"Exists", Seek.NONE}, {"Near", Seek.NONE}, {"Within", Seek.NONE},
    };

    /**
     * @return Or 로 나뉜 갈래마다 And 조건 목록. 파생 쿼리 이름이 아니면 빈 값(findAll, save, 사용자 정의 이름 등).
     *         조건이 하나도 없는 이름(findAllByOrderByIdDesc 등)은 갈래가 빈 목록이다.
     */
    public static Optional<List<List<Condition>>> parse(String methodName) {
        Matcher m = PREFIX.matcher(methodName);
        if (!m.matches()) return Optional.empty();
        String predicate = m.group(3);

        int orderBy = predicate.indexOf("OrderBy");
        if (orderBy >= 0) predicate = predicate.substring(0, orderBy);
        if (predicate.startsWith("OrderBy")) predicate = "";

        boolean allIgnoreCase = false;
        for (String s : new String[]{"AllIgnoreCase", "AllIgnoringCase"}) {
            if (predicate.endsWith(s)) {
                predicate = predicate.substring(0, predicate.length() - s.length());
                allIgnoreCase = true;
            }
        }
        if (predicate.isEmpty()) return Optional.of(List.of());

        List<List<Condition>> branches = new ArrayList<>();
        for (String branch : splitKeyword(predicate, "Or")) {
            List<Condition> conds = new ArrayList<>();
            for (String part : splitKeyword(branch, "And")) {
                if (part.isEmpty()) return Optional.empty();
                conds.add(condition(part, allIgnoreCase));
            }
            branches.add(conds);
        }
        return Optional.of(branches);
    }

    private static Condition condition(String part, boolean allIgnoreCase) {
        boolean ignoreCase = allIgnoreCase;
        for (String s : new String[]{"IgnoreCase", "IgnoringCase"}) {
            if (part.endsWith(s)) {
                part = part.substring(0, part.length() - s.length());
                ignoreCase = true;
            }
        }
        String operator = "";
        Seek seek = Seek.EQUALITY;
        for (Object[] op : OPERATORS) {
            String suffix = (String) op[0];
            // 접미사를 떼고도 속성 이름이 남아야 한다 (속성 이름 자체가 "Status" 처럼 연산자로 끝나지 않게, 앞은 소문자)
            if (part.endsWith(suffix) && part.length() > suffix.length()
                    && !Character.isUpperCase(part.charAt(part.length() - suffix.length() - 1))) {
                operator = suffix;
                seek = (Seek) op[1];
                part = part.substring(0, part.length() - suffix.length());
                break;
            }
        }
        // UPPER(col) = UPPER(?) 로 바뀌어 인덱스를 쓰지 못한다
        if (ignoreCase) seek = Seek.NONE;
        String property = Character.toLowerCase(part.charAt(0)) + part.substring(1);
        return new Condition(property, operator.isEmpty() ? "=" : operator, seek);
    }

    /** "Or"/"And" 뒤가 대문자일 때만 나눈다 (OrderNo, Android 같은 이름은 나누지 않는다). */
    private static List<String> splitKeyword(String s, String keyword) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i + keyword.length() < s.length(); i++) {
            if (s.startsWith(keyword, i)
                    && Character.isUpperCase(s.charAt(i + keyword.length()))
                    && !Character.isUpperCase(s.charAt(i - 1))) {
                out.add(s.substring(start, i));
                start = i + keyword.length();
                i = start;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
