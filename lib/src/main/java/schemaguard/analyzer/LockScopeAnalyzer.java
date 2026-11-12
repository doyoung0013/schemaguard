package schemaguard.analyzer;

import schemaguard.model.*;
import schemaguard.parser.DerivedQuery;
import schemaguard.parser.IndexCatalog;
import schemaguard.parser.QueryConditions;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 인덱스 삭제가 비관적 락 조회의 잠금 범위를 넓히는지 본다 (CHANGELOG 18).
 *
 * @Lock(PESSIMISTIC_WRITE) 조회는 MySQL 에서 SELECT ... FOR UPDATE 가 된다. InnoDB 는 조건에 맞는 행만이 아니라
 * 찾는 동안 훑은 인덱스 레코드를 잠근다(REPEATABLE READ). READ COMMITTED 는 조건에 맞지 않는 행의 잠금을 풀지만,
 * 훑는 동안 다른 트랜잭션이 잡은 행에서 기다리는 것은 같다 (CHANGELOG 20 실측). 조건 컬럼에 쓸 수 있는 인덱스가 없으면 테이블을 훑으며
 * 훑은 행을 모두 잠근다. 쿼리는 깨지지 않으므로 SQL 오류도, API 오류도 없이 동시 요청만 줄을 선다.
 *
 * 잠금 조회와 조건:
 *   파생 쿼리 + @Lock      → 메서드 이름의 조건 (DerivedQuery)
 *   JPQL @Query + @Lock    → WHERE 조건 (QueryConditions). 별칭·조인 별칭 경로를 이 테이블 컬럼으로 바꾼다
 *   네이티브 @Query        → SQL 에 FOR UPDATE / FOR SHARE 가 있으면 @Lock 이 없어도 잠금 조회. WHERE 의 컬럼을 그대로 쓴다
 *
 * 판정:
 *   1. 지운 인덱스가 이 조회에 쓰일 수 있었나: 인덱스 앞 컬럼부터 조건 컬럼과 맞는 개수(leftmost prefix) d &gt; 0
 *   2. 삭제 후 남은 인덱스(PK·UNIQUE·일반·FK) 중 가장 많이 맞는 개수 r
 *   3. r &lt; d 이면 잠금 범위 확대 가능 → LOCK_SCOPE_WIDENED (MEDIUM, 차단하지 않음)
 *      영향 경로는 그 잠금 메서드를 거치는 API 만이다.
 *
 * 남은 인덱스의 기준:
 *   --history 로 이 테이블의 정의를 읽었으면 → 마이그레이션 이력 + 이 파일 적용 후 상태
 *   아니면 → 엔티티 선언(@Id, @Table indexes/uniqueConstraints, @Column(unique)) + 이 파일의 구문.
 *           실제 DB 인덱스와 다를 수 있다고 근거에 적는다.
 */
public class LockScopeAnalyzer {

    /**
     * @param mode   리포트에 적는 잠금 모드 (PESSIMISTIC_WRITE, 네이티브면 FOR UPDATE 등)
     * @param entity 조건의 속성을 해석할 엔티티 (JPQL 의 FROM 엔티티, 아니면 리포지토리 엔티티)
     * @param query  @Query 해석 결과. 파생 쿼리면 null
     * @param caveat 근거에 덧붙일 주의 (네이티브 쿼리의 @Lock 등). 없으면 null
     */
    private record LockMethod(RepositoryInfo repo, RepositoryInfo.MethodEntry method, String mode, String entity,
                              QueryConditions.Result query, String caveat) {
        String nodeId()  { return "repo:" + repo.getClassName() + "." + method.name(); }
        String display() { return repo.getClassName() + "." + method.name(); }
    }

    private final Map<String, List<LockMethod>> lockMethodsByTable = new HashMap<>();
    private final Map<String, Map<String, String>> columnOfField = new HashMap<>();   // entity → field → column
    private final Map<String, Map<String, String>> joinColumnOfFk = new HashMap<>();  // entity → fk field → join column
    private final Map<String, List<String>> idColumns = new HashMap<>();              // table → @Id columns
    private final Map<String, List<IndexDef>> declaredIndexes = new HashMap<>();      // table → 엔티티 선언 인덱스
    private final IndexCatalog after;
    private final boolean historyRead;
    private int lockMethodCount;

    /**
     * @param after       이 마이그레이션을 적용한 뒤의 인덱스 목록 (SqlMigrationParser.catalogAfterParse)
     * @param historyRead --history 로 이전 마이그레이션을 읽었는지
     */
    public LockScopeAnalyzer(List<RepositoryInfo> repos, List<EntityMapping> entities, List<FkMapping> fkMappings,
                             List<IndexDef> entityIndexes, IndexCatalog after, boolean historyRead) {
        this.after = after != null ? after : new IndexCatalog();
        this.historyRead = historyRead;

        Map<String, String> tableOfEntity = new HashMap<>();
        for (EntityMapping em : entities) {
            String table = lower(em.getTableName());
            tableOfEntity.putIfAbsent(em.getEntityClass(), table);
            columnOfField.computeIfAbsent(em.getEntityClass(), k -> new HashMap<>())
                    .putIfAbsent(em.getFieldName(), lower(em.getColumnName()));
            if (em.isId()) idColumns.computeIfAbsent(table, k -> new ArrayList<>()).add(lower(em.getColumnName()));
            if (em.isUnique()) declaredIndexes.computeIfAbsent(table, k -> new ArrayList<>()).add(new IndexDef(table,
                    null, List.of(lower(em.getColumnName())), List.of(lower(em.getColumnName())), IndexDef.Kind.UNIQUE,
                    "@Column(unique) " + em.getEntityClass() + "." + em.getFieldName()));
        }
        for (FkMapping fk : fkMappings) {
            tableOfEntity.putIfAbsent(fk.getEntityClass(), lower(fk.getTableName()));
            joinColumnOfFk.computeIfAbsent(fk.getEntityClass(), k -> new HashMap<>())
                    .putIfAbsent(fk.getFieldName(), lower(fk.getJoinColumnName()));
        }
        for (IndexDef d : entityIndexes) declaredIndexes.computeIfAbsent(lower(d.table()), k -> new ArrayList<>()).add(d);

        for (RepositoryInfo repo : repos) {
            for (RepositoryInfo.MethodEntry m : repo.getMethods()) {
                LockMethod lm = lockMethod(repo, m, tableOfEntity);
                if (lm == null) continue;
                lockMethodCount++;
                String table = lm.query() != null && lm.query().parsed() != null && lm.query().parsed().nativeSql()
                        ? lm.query().parsed().root()                       // 네이티브: FROM 테이블
                        : tableOfEntity.get(lm.entity());
                if (table != null) lockMethodsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(lm);
            }
        }
        // 리포트·근거 순서가 입력 파일 순서와 무관하도록
        lockMethodsByTable.values().forEach(l -> l.sort(Comparator.comparing(LockMethod::nodeId)));
    }

    /** 잠금 조회이면 LockMethod, 아니면 null */
    private static LockMethod lockMethod(RepositoryInfo repo, RepositoryInfo.MethodEntry m, Map<String, String> tableOfEntity) {
        if (!m.hasQuery()) {
            return m.isPessimisticLock() ? new LockMethod(repo, m, m.lockMode(), repo.getEntityClass(), null, null) : null;
        }
        QueryConditions.Result q = QueryConditions.parse(m.query(), m.nativeQuery());
        if (!m.nativeQuery()) {
            if (!m.isPessimisticLock()) return null;
            // JPQL 의 FROM 엔티티가 매핑에 있으면 그 엔티티로, 아니면 리포지토리 엔티티로 해석한다
            String entity = q.parsed() != null && tableOfEntity.containsKey(q.parsed().root()) ? q.parsed().root() : repo.getEntityClass();
            return new LockMethod(repo, m, m.lockMode(), entity, q, null);
        }
        Matcher clause = QueryConditions.LOCK_CLAUSE.matcher(m.query());
        if (clause.find()) {
            String mode = clause.group(1) != null ? "FOR " + clause.group(1).toUpperCase(Locale.ROOT) : "LOCK IN SHARE MODE";
            return new LockMethod(repo, m, mode + " (네이티브 SQL)", repo.getEntityClass(), q, null);
        }
        if (m.isPessimisticLock()) {
            return new LockMethod(repo, m, m.lockMode() + " (네이티브)", repo.getEntityClass(), q,
                    "네이티브 쿼리의 @Lock 은 JPA 구현에 따라 적용되지 않을 수 있음(SQL 에 FOR UPDATE 가 없음)");
        }
        return null;
    }

    /** 비관적 락 리포지토리 메서드 수 (--timing 출력용) */
    public int lockMethodCount() { return lockMethodCount; }

    public List<SchemaChange> analyze(List<SchemaChange> changes) {
        Set<String> droppedHere = new HashSet<>();
        for (SchemaChange c : changes) if (isIndexDrop(c) && c.getIndexName() != null) droppedHere.add(lower(c.getIndexName()));

        // 1차: 인덱스 삭제마다 잠금 메서드별 판정. 같은 잠금 메서드에 걸리는 삭제가 여럿이면(두 인덱스를 함께 지움)
        //      그 조회가 가장 많이 쓰던 인덱스(d 가 큰 것, 같으면 앞 구문) 하나에만 경고를 붙인다.
        Map<String, Integer> bestDropForLock = new HashMap<>();
        Map<String, Integer> bestPrefixForLock = new HashMap<>();
        List<List<SchemaChange>> warningsPerChange = new ArrayList<>();
        List<SchemaChange> annotatedPerChange = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            SchemaChange c = changes.get(i);
            List<SchemaChange> warnings = new ArrayList<>();
            annotatedPerChange.add(judge(c, droppedHere, warnings));
            warningsPerChange.add(warnings);
            for (SchemaChange w : warnings) {
                int d = dropPrefixOf(w);
                Integer prev = bestPrefixForLock.get(w.getLockMethod());
                if (prev == null || d > prev) {
                    bestPrefixForLock.put(w.getLockMethod(), d);
                    bestDropForLock.put(w.getLockMethod(), i);
                }
            }
        }

        List<SchemaChange> out = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            List<SchemaChange> kept = new ArrayList<>();
            for (SchemaChange w : warningsPerChange.get(i))
                if (bestDropForLock.get(w.getLockMethod()) == i) kept.add(w);
            SchemaChange annotated = annotatedPerChange.get(i);
            if (!kept.isEmpty())
                annotated = annotated.withNoteAppended("비관적 락 조회 " + kept.size()
                        + "개의 잠금 범위가 넓어질 수 있음 (LOCK_SCOPE_WIDENED 참고)");
            out.add(annotated);
            out.addAll(kept);
        }
        return out;
    }

    /** 경고 근거에 적은 "삭제 전 n개"를 다시 읽지 않도록 판정 결과를 따로 둔다 */
    private final Map<SchemaChange, Integer> dropPrefix = new IdentityHashMap<>();

    private int dropPrefixOf(SchemaChange warning) {
        return dropPrefix.getOrDefault(warning, 0);
    }

    /** 인덱스 삭제 하나를 판정한다. 근거를 덧붙인 원래 변경을 돌려주고, 경고는 warnings 에 담는다. */
    private SchemaChange judge(SchemaChange c, Set<String> droppedHere, List<SchemaChange> warningsOut) {
        String table = lower(c.getTableName());
        List<LockMethod> locks = isIndexDrop(c) ? lockMethodsByTable.getOrDefault(table, List.of()) : List.of();
        if (locks.isEmpty()) return c;

        if (c.getIndexColumns() == null || c.getIndexColumns().isEmpty()) {
            return c.withNoteAppended("이 테이블에 비관적 락 조회 " + locks.size() + "개(" + names(locks)
                    + ")가 있지만 인덱스 정의를 몰라 잠금 범위 영향은 판단하지 못함"
                    + (historyRead ? "" : " (--history 를 주면 이전 마이그레이션에서 정의를 찾음)"));
        }

        List<String> dropped = c.getIndexColumns().stream().map(LockScopeAnalyzer::lower).toList();
        Remaining remaining = remaining(table, droppedHere);
        List<SchemaChange> warnings = new ArrayList<>();
        List<String> notJudged = new ArrayList<>();

        for (LockMethod lm : locks) {
            List<List<DerivedQuery.Condition>> branches;
            java.util.function.Function<String, String> toColumn;
            if (lm.query() != null) {
                if (lm.query().error() != null) { notJudged.add(lm.display() + "(@Query 해석 실패: " + lm.query().error() + ")"); continue; }
                branches = lm.query().parsed().branches();
                QueryConditions.Parsed q = lm.query().parsed();
                toColumn = path -> queryColumn(lm.entity(), q, path);
            } else {
                Optional<List<List<DerivedQuery.Condition>>> parsed = DerivedQuery.parse(lm.method().name());
                branches = parsed.orElse(List.of());
                toColumn = property -> column(lm.entity(), property);
            }
            if (branches.isEmpty()) { notJudged.add(lm.display() + "(조건 없음)"); continue; }

            Verdict worst = null;
            boolean unresolved = false;
            for (List<DerivedQuery.Condition> branch : branches) {
                Map<String, DerivedQuery.Seek> cols = resolve(branch, toColumn);
                if (cols == null) { unresolved = true; break; }
                int d = prefix(dropped, cols);
                if (d == 0) continue;                            // 지운 인덱스는 이 갈래에 쓰이지 않았다
                IndexDef best = null;
                int r = 0;
                for (IndexDef idx : remaining.indexes()) {
                    int p = prefix(idx.columns().stream().map(LockScopeAnalyzer::lower).toList(), cols);
                    if (p > r) { r = p; best = idx; }
                }
                if (r < d && (worst == null || r < worst.remainingPrefix()))
                    worst = new Verdict(branch, cols, d, r, best);
            }
            if (unresolved) { notJudged.add(lm.display() + "(속성을 컬럼으로 못 바꿈)"); continue; }
            if (worst != null) {
                SchemaChange w = c.lockScopeWidened(lm.nodeId(), lm.mode(), note(c, lm, worst, remaining.basis()));
                dropPrefix.put(w, worst.droppedPrefix());
                warnings.add(w);
            }
        }

        SchemaChange annotated = c;
        if (!notJudged.isEmpty())
            annotated = annotated.withNoteAppended("잠금 영향을 판단하지 않은 비관적 락 조회: " + String.join(", ", notJudged));
        warningsOut.addAll(warnings);
        return annotated;
    }

    private record Verdict(List<DerivedQuery.Condition> branch, Map<String, DerivedQuery.Seek> cols,
                           int droppedPrefix, int remainingPrefix, IndexDef best) {}

    private record Remaining(List<IndexDef> indexes, String basis) {}

    /** 같은 점수의 대체 인덱스가 여럿이면 이름순으로 첫 번째를 근거로 적는다 */
    private static final Comparator<IndexDef> BY_NAME =
            Comparator.comparing((IndexDef d) -> d.name() != null ? d.name() : d.aliases().isEmpty() ? "" : d.aliases().get(0))
                    .thenComparing(d -> String.join(",", d.columns()));

    /** 삭제 후 남는 인덱스. 이 마이그레이션에서 지운 이름은 엔티티 선언에 남아 있어도 뺀다. */
    private Remaining remaining(String table, Set<String> droppedHere) {
        List<IndexDef> out = new ArrayList<>(after.indexesOf(table));
        if (historyRead && after.knowsTable(table)) {
            out.sort(BY_NAME);
            return new Remaining(out, "마이그레이션 이력(--history)과 이 파일을 적용한 뒤의 인덱스");
        }
        for (IndexDef d : declaredIndexes.getOrDefault(table, List.of())) {
            boolean droppedNow = droppedHere.stream().anyMatch(d::answersTo);
            if (!droppedNow) out.add(d);
        }
        List<String> id = idColumns.get(table);
        if (id != null) out.add(new IndexDef(table, null, List.of("primary"), id, IndexDef.Kind.PRIMARY_KEY, "@Id"));
        out.sort(BY_NAME);
        return new Remaining(out, "엔티티 선언(@Id, @Table indexes/uniqueConstraints, @Column(unique))과 이 파일의 구문"
                + (historyRead ? "" : ". --history 가 없어 실제 DB 인덱스와 다를 수 있음"));
    }

    /**
     * 인덱스 앞 컬럼부터 조건 컬럼과 맞는 개수 (leftmost prefix).
     * 같음/IN 조건이면 다음 컬럼도 쓸 수 있고, 범위 조건이면 그 컬럼까지만 쓴다.
     */
    public static int prefix(List<String> indexColumns, Map<String, DerivedQuery.Seek> cols) {
        int n = 0;
        for (String col : indexColumns) {
            DerivedQuery.Seek s = cols.get(col);
            if (s == DerivedQuery.Seek.EQUALITY) { n++; continue; }
            if (s == DerivedQuery.Seek.RANGE) n++;
            break;
        }
        return n;
    }

    /** 조건의 속성 → 컬럼. 하나라도 컬럼으로 바꾸지 못하면 null (판단하지 않는다). */
    private static Map<String, DerivedQuery.Seek> resolve(List<DerivedQuery.Condition> branch,
                                                          java.util.function.Function<String, String> toColumn) {
        Map<String, DerivedQuery.Seek> out = new LinkedHashMap<>();
        for (DerivedQuery.Condition c : branch) {
            String col = toColumn.apply(c.property());
            if (col == null) return null;
            // 같은 컬럼에 조건이 여럿이면 인덱스를 더 잘 쓰는 쪽
            out.merge(col, c.seek(), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return out;
    }

    /**
     * 엔티티 속성 → 이 테이블의 컬럼.
     *   email           → email 필드의 컬럼
     *   product         → product 연관의 조인 컬럼 (findByProduct(Product p))
     *   productId, product_Id → product 연관의 조인 컬럼 (연관 엔티티의 id = 이 테이블의 FK 컬럼)
     * 연관 엔티티의 다른 속성(productName → product.name)은 조인이 필요해 이 테이블 컬럼이 아니므로 null.
     */
    private String column(String entity, String property) {
        Map<String, String> fields = columnOfField.getOrDefault(entity, Map.of());
        Map<String, String> fks = joinColumnOfFk.getOrDefault(entity, Map.of());
        if (fields.containsKey(property)) return fields.get(property);
        if (fks.containsKey(property)) return fks.get(property);
        int us = property.indexOf('_');
        if (us > 0) {
            String head = property.substring(0, us), tail = property.substring(us + 1);
            return fks.containsKey(head) && tail.equalsIgnoreCase("id") ? fks.get(head) : null;
        }
        for (Map.Entry<String, String> fk : fks.entrySet()) {
            if (property.startsWith(fk.getKey()) && property.substring(fk.getKey().length()).equals("Id"))
                return fk.getValue();
        }
        return null;
    }

    /**
     * @Query 조건의 경로 → 이 테이블의 컬럼.
     *   JPQL  : s.status → status 필드 컬럼, s.product → / s.product.id → product 조인 컬럼,
     *           join s.product p 이후 p.id → product 조인 컬럼, 별칭 없는 status → 루트 속성
     *           p.name 처럼 조인한 엔티티의 다른 속성은 다른 테이블 컬럼이라 null
     *   네이티브: product_id, s.product_id(루트 별칭 또는 테이블 이름) → 그대로. 조인한 테이블의 컬럼은 null
     */
    private String queryColumn(String entity, QueryConditions.Parsed q, String path) {
        String[] seg = path.split("\\.");
        if (q.nativeSql()) {
            if (seg.length == 1) return lower(seg[0]);
            if (seg.length == 2 && (seg[0].equals(q.rootAlias()) || seg[0].equalsIgnoreCase(q.root()))) return lower(seg[1]);
            return null;
        }
        List<String> rel = new ArrayList<>();
        if (seg.length == 1) {
            rel.add(seg[0]);
        } else if (seg[0].equals(q.rootAlias())) {
            rel.addAll(Arrays.asList(seg).subList(1, seg.length));
        } else if (q.joinAliases().containsKey(seg[0])) {
            String joined = q.joinAliases().get(seg[0]);
            if (joined == null) return null;
            rel.addAll(Arrays.asList(joined.split("\\.")));
            rel.addAll(Arrays.asList(seg).subList(1, seg.length));
        } else {
            return null;
        }
        if (rel.size() == 1) return column(entity, rel.get(0));
        if (rel.size() == 2 && rel.get(1).equalsIgnoreCase("id")) {
            return joinColumnOfFk.getOrDefault(entity, Map.of()).get(rel.get(0));
        }
        return null;
    }

    private static String note(SchemaChange c, LockMethod lm, Verdict v, String basis) {
        StringBuilder conds = new StringBuilder();
        v.cols().forEach((col, seek) -> {
            if (!conds.isEmpty()) conds.append(", ");
            conds.append(col).append(seek == DerivedQuery.Seek.RANGE ? " (범위)" : seek == DerivedQuery.Seek.NONE ? " (인덱스 못 씀)" : "");
        });
        String usedCols = String.join(", ", c.getIndexColumns().subList(0, v.droppedPrefix()));
        String now = v.remainingPrefix() == 0
                ? "남은 인덱스 중 이 조건에 쓸 수 있는 것이 없음 → 테이블을 훑으며 훑은 행을 모두 잠글 수 있음"
                : "남은 인덱스 " + name(v.best()) + " 로는 앞 " + v.remainingPrefix() + "개 컬럼만 쓸 수 있음(삭제 전 "
                  + v.droppedPrefix() + "개) → 앞 컬럼 값이 같은 행을 모두 잠글 수 있음";
        return "비관적 락 조회 " + lm.display() + " (" + lm.mode() + (lm.query() != null ? ", @Query" : "")
                + ", 조건: " + conds + ") 가 쓸 수 있던 인덱스 "
                + c.getIndexName() + " (" + usedCols + ") 삭제. " + now
                + ". MySQL InnoDB 는 찾는 동안 훑은 인덱스 레코드를 잠그므로(READ COMMITTED 도 훑는 중 다른 요청이 잡은 행에서 기다림) 동시 요청이 잠금 대기·타임아웃으로 실패할 수 있음"
                + ". 남은 인덱스 기준: " + basis
                + (lm.caveat() != null ? ". 주의: " + lm.caveat() : "");
    }

    private static String name(IndexDef d) {
        if (d == null) return "-";
        String n = d.name() != null ? d.name() : d.kind() == IndexDef.Kind.PRIMARY_KEY ? "PRIMARY" : "(이름 없음)";
        return n + " (" + String.join(", ", d.columns()) + ")";
    }

    private static String names(List<LockMethod> locks) {
        return String.join(", ", locks.stream().map(LockMethod::display).toList());
    }

    private static boolean isIndexDrop(SchemaChange c) {
        return c.getChangeType() == ChangeType.DROP_INDEX || c.getChangeType() == ChangeType.DROP_UNIQUE;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
