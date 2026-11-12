package schemaguard.parser;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import schemaguard.parser.DerivedQuery.Condition;
import schemaguard.parser.DerivedQuery.Seek;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Query 의 WHERE 조건을 조회 조건으로 나눈다 (CHANGELOG 18, 2단계).
 *
 *   JPQL   : select s from Stock s join s.product p where p.id = :productId and s.status = :status
 *            → 루트 Stock, 별칭 s, 조인 별칭 p → product, 갈래 [[p.id =, s.status =]]
 *   네이티브: select * from stocks where product_id = ?1 and status = ?2 for update
 *            → 루트 테이블 stocks, 갈래 [[product_id =, status =]], FOR UPDATE 있음
 *
 * WHERE 식만 떼어 JSQLParser 로 읽는다(전체를 다 읽어야 성공. 일부만 읽고 뒤를 버리지 않는다).
 * OR 는 갈래로 펼친다: a AND (b OR c) → [a, b], [a, c].
 * 경로(s.product.id)를 컬럼으로 바꾸는 일은 엔티티 매핑을 아는 LockScopeAnalyzer 가 한다.
 * 연산자별 인덱스 사용 가능 여부는 DerivedQuery 와 같은 기준이다.
 */
public final class QueryConditions {

    /**
     * @param root         JPQL 이면 엔티티 이름(Stock), 네이티브면 테이블 이름(stocks)
     * @param rootAlias    FROM 의 별칭. 없으면 null
     * @param joinAliases  JPQL 조인 별칭 → 루트 기준 경로 (p → "product"). 네이티브 조인 별칭은 값이 null(다른 테이블)
     * @param branches     OR 갈래마다 AND 조건. Condition.property 는 별칭을 포함한 원래 경로(s.product.id)
     * @param lockClause   SQL 에 FOR UPDATE / FOR SHARE / LOCK IN SHARE MODE 가 있음 (네이티브)
     */
    public record Parsed(String root, String rootAlias, Map<String, String> joinAliases,
                         List<List<Condition>> branches, boolean nativeSql, boolean lockClause) {}

    /** parsed 와 error 중 하나만 있다 */
    public record Result(Parsed parsed, String error) {
        static Result fail(String why) { return new Result(null, why); }
    }

    private QueryConditions() {}

    private static final int MAX_BRANCHES = 32;

    private static final Pattern FROM = Pattern.compile(
            "(?i)\\bfrom\\s+([\\w.$`\"]+)(?:\\s+(?:as\\s+)?(?!(?:where|join|left|right|inner|outer|cross|on|order|group|for|limit|fetch)\\b)(\\w+))?");
    private static final Pattern JPQL_JOIN = Pattern.compile(
            "(?i)\\bjoin\\s+(?:fetch\\s+)?(\\w+)\\.([\\w.]+)(?:\\s+(?:as\\s+)?(?!(?:where|join|left|right|inner|outer|on|order|group|fetch)\\b)(\\w+))?");
    private static final Pattern NATIVE_JOIN = Pattern.compile(
            "(?i)\\bjoin\\s+([\\w.$`\"]+)(?:\\s+(?:as\\s+)?(?!(?:on|using|where)\\b)(\\w+))?");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b");
    private static final Pattern WHERE_END = Pattern.compile(
            "(?i)\\b(order\\s+by|group\\s+by|having|limit|for\\s+update|for\\s+share|lock\\s+in\\s+share\\s+mode)\\b");
    public static final Pattern LOCK_CLAUSE = Pattern.compile(
            "(?i)\\bfor\\s+(update|share)\\b|\\block\\s+in\\s+share\\s+mode\\b");

    public static Result parse(String query, boolean nativeSql) {
        if (query == null || query.isBlank()) return Result.fail("쿼리 없음");
        Matcher from = FROM.matcher(query);
        if (!from.find()) return Result.fail("FROM 을 찾지 못함");
        String root = strip(from.group(1));
        if (!nativeSql) root = root.substring(root.lastIndexOf('.') + 1);       // 패키지까지 쓴 엔티티 이름
        else root = SqlMigrationParser.ident(root);
        String rootAlias = from.group(2);

        Map<String, String> joins = new LinkedHashMap<>();
        if (nativeSql) {
            Matcher j = NATIVE_JOIN.matcher(query);
            while (j.find()) {
                joins.put(j.group(2) != null ? j.group(2) : SqlMigrationParser.ident(strip(j.group(1))), null);
            }
        } else {
            Matcher j = JPQL_JOIN.matcher(query);
            while (j.find()) {
                String base = j.group(1), attr = j.group(2), alias = j.group(3);
                String basePath = base.equals(rootAlias) ? "" : joins.get(base);
                if (alias == null) continue;
                joins.put(alias, basePath == null ? null : basePath.isEmpty() ? attr : basePath + "." + attr);
            }
        }

        boolean lock = LOCK_CLAUSE.matcher(query).find();
        Matcher w = WHERE.matcher(query);
        if (!w.find()) return new Result(new Parsed(root, rootAlias, joins, List.of(), nativeSql, lock), null);
        String rest = query.substring(w.end());
        Matcher end = WHERE_END.matcher(rest);
        String where = (end.find() ? rest.substring(0, end.start()) : rest).trim();

        Expression expr;
        try {
            expr = CCJSqlParserUtil.parseCondExpression(where, false);
        } catch (Exception | Error e) {
            return Result.fail("WHERE 를 읽지 못함: " + where);
        }
        List<List<Condition>> branches = dnf(expr);
        if (branches == null) return Result.fail("OR 조건이 너무 많음 (" + MAX_BRANCHES + "갈래 초과)");
        return new Result(new Parsed(root, rootAlias, joins, branches, nativeSql, lock), null);
    }

    /** OR 를 갈래로 펼친다 (논리합 표준형). 갈래가 너무 많으면 null. */
    private static List<List<Condition>> dnf(Expression e) {
        if (e instanceof Parenthesis p) return dnf(p.getExpression());
        if (e instanceof OrExpression or) {
            List<List<Condition>> l = dnf(or.getLeftExpression()), r = dnf(or.getRightExpression());
            if (l == null || r == null || l.size() + r.size() > MAX_BRANCHES) return null;
            List<List<Condition>> out = new ArrayList<>(l);
            out.addAll(r);
            return out;
        }
        if (e instanceof AndExpression and) {
            List<List<Condition>> l = dnf(and.getLeftExpression()), r = dnf(and.getRightExpression());
            if (l == null || r == null || (long) l.size() * r.size() > MAX_BRANCHES) return null;
            List<List<Condition>> out = new ArrayList<>();
            for (List<Condition> a : l) for (List<Condition> b : r) {
                List<Condition> both = new ArrayList<>(a);
                both.addAll(b);
                out.add(both);
            }
            return out;
        }
        List<List<Condition>> single = new ArrayList<>();
        single.add(conditions(e));
        return single;
    }

    /** 조건 하나. 인덱스로 찾을 수 있는 형태(컬럼 OP 값)가 아니면 안에 든 컬럼을 모두 "못 씀"으로 둔다. */
    private static List<Condition> conditions(Expression e) {
        if (e instanceof NotExpression n) return none(n.getExpression(), "NOT");

        if (e instanceof EqualsTo eq) return compare(eq, Seek.EQUALITY, "=");
        if (e instanceof NotEqualsTo ne) return compare(ne, Seek.NONE, "<>");
        if (e instanceof GreaterThan g) return compare(g, Seek.RANGE, ">");
        if (e instanceof GreaterThanEquals g) return compare(g, Seek.RANGE, ">=");
        if (e instanceof MinorThan m) return compare(m, Seek.RANGE, "<");
        if (e instanceof MinorThanEquals m) return compare(m, Seek.RANGE, "<=");

        if (e instanceof InExpression in) {
            if (in.getLeftExpression() instanceof Column c && !in.isNot()) return List.of(cond(c, "IN", Seek.EQUALITY));
            return none(e, "NOT IN");
        }
        if (e instanceof Between b) {
            if (b.getLeftExpression() instanceof Column c && !b.isNot()) return List.of(cond(c, "BETWEEN", Seek.RANGE));
            return none(e, "NOT BETWEEN");
        }
        if (e instanceof IsNullExpression isNull) {
            if (isNull.getLeftExpression() instanceof Column c && !isNull.isNot()) return List.of(cond(c, "IS NULL", Seek.EQUALITY));
            return none(e, "IS NOT NULL");
        }
        if (e instanceof LikeExpression like) {
            // 'abc%' 처럼 앞이 고정된 문자열이면 범위로 찾는다. 파라미터는 패턴을 모른다(앞이 % 일 수 있다).
            boolean prefix = like.getRightExpression() instanceof StringValue sv
                    && !sv.getValue().isEmpty() && sv.getValue().charAt(0) != '%' && sv.getValue().charAt(0) != '_';
            if (like.getLeftExpression() instanceof Column c && !like.isNot() && prefix)
                return List.of(cond(c, "LIKE 'x%'", Seek.RANGE));
            return none(e, "LIKE");
        }
        // 그 밖(EXISTS, MEMBER OF, 함수 비교 등): 안의 컬럼은 이 조건으로 인덱스를 쓰지 못한다
        return none(e, "기타");
    }

    private static List<Condition> compare(BinaryExpression b, Seek seek, String op) {
        Expression l = b.getLeftExpression(), r = b.getRightExpression();
        boolean lc = l instanceof Column && !isLiteralColumn((Column) l), rc = r instanceof Column && !isLiteralColumn((Column) r);
        if (lc && !rc && !containsColumn(r)) return List.of(cond((Column) l, op, seek));
        if (rc && !lc && !containsColumn(l)) return List.of(cond((Column) r, op, seek));
        // 컬럼끼리 비교(조인 조건)나 함수로 감싼 컬럼(UPPER(s.email))은 이 조건으로 찾지 못한다
        return none(b, op);
    }

    private static Condition cond(Column c, String op, Seek seek) {
        return new Condition(c.getFullyQualifiedName(), op, seek);
    }

    /** 식 안의 컬럼을 모두 "못 씀"으로 */
    private static List<Condition> none(Expression e, String op) {
        List<Condition> out = new ArrayList<>();
        collectColumns(e, c -> out.add(new Condition(c.getFullyQualifiedName(), op, Seek.NONE)));
        return out;
    }

    private static boolean containsColumn(Expression e) {
        boolean[] found = {false};
        collectColumns(e, c -> found[0] = true);
        return found[0];
    }

    private static void collectColumns(Expression e, java.util.function.Consumer<Column> sink) {
        e.accept(new net.sf.jsqlparser.util.deparser.ExpressionDeParser() {
            @Override public void visit(Column column) {
                if (!isLiteralColumn(column)) sink.accept(column);
                super.visit(column);
            }
            @Override public void visit(Function function) {
                super.visit(function);
            }
        });
    }

    /** JSQLParser 는 true/false 를 컬럼으로 읽는다 */
    private static boolean isLiteralColumn(Column c) {
        String n = c.getFullyQualifiedName();
        return n.equalsIgnoreCase("true") || n.equalsIgnoreCase("false");
    }

    private static String strip(String s) {
        return s.replaceAll("[`\"]", "");
    }
}
