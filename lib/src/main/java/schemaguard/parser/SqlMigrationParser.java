package schemaguard.parser;

import schemaguard.model.ChangeType;
import schemaguard.model.RiskLevel;
import schemaguard.model.SchemaChange;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.drop.Drop;
import schemaguard.model.IndexDef;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 마이그레이션 파일을 파싱하여 SchemaChange 목록을 추출한다.
 *
 * 파일을 구문(;) 단위로 나눠 하나씩 파싱한다. 한 구문이 실패해도 나머지 구문의
 * 변경 사항은 그대로 탐지하고, 실패한 구문은 ParseResult.failures()로 돌려준다.
 * 호출 측(CLI)은 실패가 하나라도 있으면 "분석 불완전"으로 보고 통과시키지 않아야 한다.
 *
 * JSQLParser가 일부 MySQL FK DDL 문법을 파싱하지 못하는 문제를 보완하기 위해
 * DROP FOREIGN KEY / ADD FOREIGN KEY / DROP KEY 구문은 정규식으로 먼저 처리한다.
 *
 * 인덱스·제약 삭제(DROP INDEX, DROP CONSTRAINT)는 이름만 있어서 IndexCatalog(이전 마이그레이션)로 정의를 찾는다.
 * 파일 안의 구문도 순서대로 카탈로그에 반영하므로, 같은 파일에서 만들고 지운 인덱스도 찾는다.
 */
public class SqlMigrationParser {

    /** 식별자: users, `users`, "users", db.users, `db`.`users` */
    private static final String IDENT = "([`\"\\w.]+)";

    /** ALTER TABLE t DROP FOREIGN KEY fk_name */
    static final Pattern DROP_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + IDENT + "\\s+DROP\\s+FOREIGN\\s+KEY\\s+" + IDENT + "\\s*",
            Pattern.CASE_INSENSITIVE
    );

    /** ALTER TABLE t ADD [CONSTRAINT name] FOREIGN KEY (col) REFERENCES ref(rc) [ON DELETE|UPDATE ...] */
    static final Pattern ADD_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + IDENT + "\\s+ADD\\s+(?:CONSTRAINT\\s+" + IDENT + "\\s+)?" +
                    "FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+" + IDENT + "\\s*\\(([^)]+)\\)" +
                    "(?:\\s+ON\\s+(?:DELETE|UPDATE)\\s+" +
                    "(?:CASCADE|RESTRICT|SET\\s+NULL|SET\\s+DEFAULT|NO\\s+ACTION))*\\s*",
            Pattern.CASE_INSENSITIVE
    );

    /** ALTER TABLE t DROP INDEX|KEY name. JSQLParser 4.7 은 DROP KEY 를 파싱하지 못한다. */
    static final Pattern DROP_INDEX_ALTER_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + IDENT + "\\s+DROP\\s+(?:INDEX|KEY)\\s+" + IDENT + "\\s*",
            Pattern.CASE_INSENSITIVE
    );

    /** FK 역할 컬럼명 판단: suffix가 _id, _fk, _key */
    private static final Pattern FK_COLUMN_SUFFIX =
            Pattern.compile(".*_(id|fk|key)$", Pattern.CASE_INSENSITIVE);

    /** 파싱하지 못한 구문. line은 SQL 파일 내 구문 시작 줄(1부터). */
    public record ParseFailure(int line, String statement, String reason) {}

    /**
     * @param changes         탐지된 스키마 변경
     * @param failures        파싱 실패한 구문 (비어 있지 않으면 분석 결과는 불완전하다)
     * @param totalStatements 파일 내 전체 구문 수 (주석/빈 구문 제외)
     */
    public record ParseResult(List<SchemaChange> changes,
                              List<ParseFailure> failures,
                              int totalStatements) {
        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    private record SqlStatement(int line, String text) {}

    /** 이전 마이그레이션에서 알아 둔 인덱스·제약. 파싱할 때마다 복사해서 쓰고, 원본은 바꾸지 않는다. */
    private final IndexCatalog history;
    /** parseSQL 한 번 동안 쓰는 카탈로그 (history + 이 파일의 앞선 구문) */
    private IndexCatalog catalog;

    /** 이전 마이그레이션을 모를 때. 인덱스 삭제는 엔티티 선언으로 분류하거나, 그래도 모르면 보수적으로 본다. */
    public SqlMigrationParser() {
        this(new IndexCatalog());
    }

    public SqlMigrationParser(IndexCatalog history) {
        this.history = history;
    }

    public ParseResult parse(File sqlFile) throws Exception {
        return parseSQL(Files.readString(sqlFile.toPath()));
    }

    public ParseResult parseSQL(String sql) {
        List<SchemaChange> changes = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        List<SqlStatement> statements = splitStatements(sql);
        catalog = history.copy();

        for (SqlStatement stmt : statements) {
            try {
                changes.addAll(parseStatement(stmt.text()));
                // 변경은 이 구문 "이전" 스키마로 판단하고, 그다음에 반영한다 (DROP INDEX 는 지우기 전 정의를 봐야 한다).
                catalog.apply(stmt.text(), "this migration");
            } catch (Exception e) {
                failures.add(new ParseFailure(stmt.line(), stmt.text(), firstLine(e)));
            }
        }

        promoteFkModify(changes);
        cancelUniqueRecreate(changes);

        return new ParseResult(changes, failures, statements.size());
    }

    /** 구문 하나를 파싱한다. 파싱할 수 없으면 예외를 던진다. */
    private List<SchemaChange> parseStatement(String sql) throws Exception {
        Matcher dropFk = DROP_FK_PATTERN.matcher(sql);
        if (dropFk.matches()) {
            return List.of(new SchemaChange(
                    ident(dropFk.group(1)),
                    null,
                    null,
                    ChangeType.DROP_FOREIGN_KEY,
                    // 제약만 사라진다. 기존 쿼리는 깨지지 않고, 이후 참조 무결성을 DB가 보장하지 않을 뿐이다.
                    RiskLevel.LOW,
                    ident(dropFk.group(2)),
                    null,
                    null
            ));
        }

        Matcher addFk = ADD_FK_PATTERN.matcher(sql);
        if (addFk.matches()) {
            return List.of(new SchemaChange(
                    ident(addFk.group(1)),
                    columnList(addFk.group(3)),
                    null,
                    ChangeType.ADD_FOREIGN_KEY,
                    RiskLevel.MEDIUM,
                    addFk.group(2) != null ? ident(addFk.group(2)) : null,
                    ident(addFk.group(4)),
                    columnList(addFk.group(5))
            ));
        }

        Matcher dropIndex = DROP_INDEX_ALTER_PATTERN.matcher(sql);
        if (dropIndex.matches()) {
            return List.of(indexDrop(ident(dropIndex.group(1)), ident(dropIndex.group(2))));
        }

        Statement stmt = CCJSqlParserUtil.parse(sql);
        if (stmt instanceof Alter alter) {
            return handleAlter(alter);
        }
        if (stmt instanceof Drop drop) {
            return List.of(handleDrop(drop));
        }
        if (stmt instanceof CreateIndex ci) {
            return handleCreateIndex(ci);
        }
        if (stmt instanceof RenameTableStatement rename) {
            // MySQL: RENAME TABLE a TO b [, c TO d]
            List<SchemaChange> result = new ArrayList<>();
            for (Map.Entry<Table, Table> e : rename.getTableNames()) {
                result.add(new SchemaChange(
                        ident(e.getKey().getName()),
                        null,
                        ident(e.getValue().getName()),
                        ChangeType.RENAME_TABLE,
                        RiskLevel.HIGH
                ));
            }
            return result;
        }
        return List.of();
    }

    /** 구문 텍스트만 (IndexCatalog 가 이전 마이그레이션을 적용할 때 쓴다). */
    static List<String> splitStatementTexts(String sql) {
        return splitStatements(sql).stream().map(SqlStatement::text).toList();
    }

    /**
     * SQL을 세미콜론 기준으로 구문 단위로 나눈다.
     * 따옴표('...', "...", `...`) 안의 세미콜론은 구분자로 보지 않고,
     * 주석(--, #, / * * /)은 제거한다. 각 구문의 시작 줄 번호를 함께 기록한다.
     */
    private static List<SqlStatement> splitStatements(String sql) {
        List<SqlStatement> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int line = 1;
        int startLine = -1;
        char quote = 0;
        int n = sql.length();

        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;

            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && quote != '`' && i + 1 < n) {       // MySQL 백슬래시 이스케이프
                    cur.append(next);
                    i++;
                    if (next == '\n') line++;
                } else if (c == quote) {
                    if (next == quote) {                            // '' 이스케이프
                        cur.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                } else if (c == '\n') {
                    line++;
                }
                continue;
            }

            if ((c == '-' && next == '-') || c == '#') {            // 한 줄 주석
                while (i < n && sql.charAt(i) != '\n') i++;
                if (i < n) {
                    cur.append('\n');
                    line++;
                }
                continue;
            }

            if (c == '/' && next == '*') {                          // 블록 주석
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) {
                    if (sql.charAt(k) == '\n') line++;
                }
                cur.append(' ');
                i = stop - 1;
                continue;
            }

            if (c == ';') {
                flush(cur, startLine, result);
                startLine = -1;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') quote = c;
            if (startLine < 0 && !Character.isWhitespace(c)) startLine = line;
            cur.append(c);
            if (c == '\n') line++;
        }

        flush(cur, startLine, result);
        return result;
    }

    private static void flush(StringBuilder cur, int startLine, List<SqlStatement> out) {
        String text = cur.toString().trim();
        if (!text.isEmpty()) {
            out.add(new SqlStatement(startLine, text));
        }
        cur.setLength(0);
    }

    /**
     * DROP_FOREIGN_KEY + ADD_FOREIGN_KEY가 같은 테이블에 함께 있으면
     * MODIFY_FK_REFERENCE 하나로 합친다.
     */
    private void promoteFkModify(List<SchemaChange> changes) {
        List<SchemaChange> drops = new ArrayList<>(changes.stream()
                .filter(c -> c.getChangeType() == ChangeType.DROP_FOREIGN_KEY)
                .toList());

        List<SchemaChange> adds = new ArrayList<>(changes.stream()
                .filter(c -> c.getChangeType() == ChangeType.ADD_FOREIGN_KEY)
                .toList());

        for (SchemaChange drop : drops) {
            adds.stream()
                    .filter(add -> add.getTableName().equals(drop.getTableName()))
                    .findFirst()
                    .ifPresent(add -> {
                        changes.remove(drop);
                        changes.remove(add);

                        changes.add(new SchemaChange(
                                drop.getTableName(),
                                add.getColumnName(),
                                null,
                                ChangeType.MODIFY_FK_REFERENCE,
                                // 읽기는 그대로 동작한다. 새 참조 대상에 없는 값을 쓰는 INSERT/UPDATE 만 실패한다.
                                RiskLevel.MEDIUM,
                                add.getFkName(),
                                add.getReferencedTable(),
                                add.getReferencedColumn()
                        ));
                    });
        }
    }

    private List<SchemaChange> handleAlter(Alter alter) {
        List<SchemaChange> result = new ArrayList<>();
        String table = ident(alter.getTable().getName());

        for (AlterExpression expr : alter.getAlterExpressions()) {
            String operation = expr.getOperation().name().toUpperCase();
            String exprStr = expr.toString().toUpperCase();

            switch (operation) {
                case "DROP" -> {
                    if (expr.getColumnName() != null) {
                        String col = ident(expr.getColumnName());

                        ChangeType type = isFkColumn(col)
                                ? ChangeType.DROP_FK_COLUMN
                                : ChangeType.DROP_COLUMN;

                        result.add(new SchemaChange(
                                table,
                                col,
                                type,
                                RiskLevel.HIGH
                        ));
                    } else if (expr.getIndex() != null && expr.getIndex().getName() != null) {
                        // DROP INDEX name (전에는 컬럼 이름이 없다는 이유로 그냥 지나갔다)
                        result.add(indexDrop(table, ident(expr.getIndex().getName())));
                    } else if (expr.getConstraintName() != null) {
                        // DROP CONSTRAINT name: UNIQUE 일 수도, FK(PostgreSQL)일 수도, CHECK 일 수도 있다
                        result.add(indexDrop(table, ident(expr.getConstraintName())));
                    } else {
                        throw new IllegalArgumentException("Unrecognized ALTER TABLE DROP clause: " + expr);
                    }
                }

                case "DROP_UNIQUE" -> {
                    // ALTER TABLE t DROP UNIQUE (a, b): JSQLParser 는 컬럼을 pkColumns 에 담는다
                    List<String> cols = expr.getPkColumns() == null ? List.of()
                            : expr.getPkColumns().stream().map(SqlMigrationParser::ident).toList();
                    result.add(dropUnique(table, null, cols, "UNIQUE (" + String.join(", ", cols) + ") 삭제"));
                }

                case "DROP_PRIMARY_KEY" -> {
                    List<String> cols = catalog.primaryKey(table).map(IndexDef::columns).orElse(null);
                    result.add(cols == null
                            ? new SchemaChange(table, null, ChangeType.DROP_UNIQUE, RiskLevel.HIGH)
                                    .withIndex("primary", List.of())
                                    .reclassify(ChangeType.DROP_UNIQUE, RiskLevel.HIGH,
                                            "PRIMARY KEY 삭제: 같은 id 를 가진 행을 DB 가 막지 않음")
                            : dropUnique(table, "primary", cols, "PRIMARY KEY 삭제"));
                }

                case "RENAME" -> {
                    if (expr.getColumnName() != null && expr.getColumnOldName() != null) {
                        result.add(new SchemaChange(
                                table,
                                ident(expr.getColumnOldName()),
                                ident(expr.getColumnName()),
                                ChangeType.RENAME_COLUMN,
                                RiskLevel.HIGH
                        ));
                    }
                }

                case "RENAME_TABLE" -> {
                    // ALTER TABLE users RENAME TO members
                    result.add(new SchemaChange(
                            table,
                            null,
                            expr.getNewTableName() != null ? ident(expr.getNewTableName()) : null,
                            ChangeType.RENAME_TABLE,
                            RiskLevel.HIGH
                    ));
                }

                case "ALTER" -> {
                    // ALTER [COLUMN] col SET NOT NULL | TYPE t | SET DATA TYPE t (PostgreSQL 등)
                    // DROP NOT NULL, SET/DROP DEFAULT는 기존 코드를 깨지 않으므로 무시한다.
                    if (expr.getColDataTypeList() == null || expr.getColDataTypeList().isEmpty()) {
                        break;
                    }
                    String col = ident(expr.getColDataTypeList().get(0).getColumnName());
                    String norm = " " + exprStr.replaceAll("\\s+", " ") + " ";

                    if (norm.contains(" SET NOT NULL ")) {
                        result.add(new SchemaChange(table, col, ChangeType.ADD_NOT_NULL, RiskLevel.MEDIUM));
                    } else if (norm.contains(" SET DATA TYPE ") || norm.contains(" TYPE ")) {
                        result.add(new SchemaChange(table, col, ChangeType.MODIFY_TYPE, RiskLevel.HIGH)
                                .withColumnDef(columnDef(expr.getColDataTypeList().get(0))));
                    }
                }

                case "MODIFY", "CHANGE" -> {
                    if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                        AlterExpression.ColumnDataType cdt = expr.getColDataTypeList().get(0);
                        String col = ident(cdt.getColumnName());

                        // 최종 분류(호환/축소/NOT NULL/비호환)는 엔티티 매핑과 비교하는 ChangeClassifier 가 한다.
                        result.add(new SchemaChange(
                                table,
                                col,
                                ChangeType.MODIFY_TYPE,
                                RiskLevel.HIGH
                        ).withColumnDef(columnDef(cdt)));
                    }
                }

                case "ADD" -> {
                    if (exprStr.contains("UNIQUE")) {
                        List<String> cols = uniqueColumns(expr);
                        result.add(new SchemaChange(
                                table,
                                cols.isEmpty() ? extractFirstColumnName(expr) : cols.get(0),
                                ChangeType.ADD_UNIQUE,
                                RiskLevel.MEDIUM
                        ).withIndex(uniqueName(expr), cols));
                    } else if (exprStr.contains("NOT NULL") && !exprStr.contains("DEFAULT")) {
                        // DEFAULT 없는 NOT NULL 컬럼 추가: 엔티티가 모르는 컬럼이라 INSERT 에서 값이 빠져 실패한다.
                        result.add(new SchemaChange(
                                table,
                                extractFirstColumnName(expr),
                                ChangeType.ADD_NOT_NULL,
                                RiskLevel.MEDIUM
                        ));
                    } else if (expr.getColDataTypeList() != null
                            && !expr.getColDataTypeList().isEmpty()) {
                        String col = ident(expr.getColDataTypeList()
                                .get(0)
                                .getColumnName());

                        result.add(new SchemaChange(
                                table,
                                col,
                                ChangeType.ADD_COLUMN,
                                RiskLevel.LOW
                        ));
                    }
                }

                // JSQLParser는 모르는 ALTER 절을 예외 없이 UNSPECIFIC으로 돌려준다.
                // 이해하지 못한 구문을 "변경 없음"으로 넘기지 않도록 실패로 처리한다.
                case "UNSPECIFIC" -> throw new IllegalArgumentException(
                        "Unrecognized ALTER TABLE clause: " + expr.getOptionalSpecifier());

                default -> {
                    // 무시
                }
            }
        }

        return result;
    }

    /**
     * DROP 구문. 전에는 대상 종류를 보지 않아서 DROP INDEX idx ON users 를 "idx 테이블 삭제"로 처리했다.
     */
    private SchemaChange handleDrop(Drop drop) {
        String type = drop.getType() == null ? "" : drop.getType().toUpperCase();
        String name = ident(drop.getName().getName());
        return switch (type) {
            // 엔티티는 뷰에도 매핑될 수 있다. 뷰를 지우면 테이블을 지운 것과 같은 범위가 깨진다.
            case "TABLE", "VIEW" -> new SchemaChange(name, null, ChangeType.DROP_TABLE, RiskLevel.HIGH);
            case "INDEX" -> indexDrop(dropIndexTable(drop), name);
            // SEQUENCE(@SequenceGenerator), TRIGGER 등: 영향을 판단하지 못하는 대상은 통과시키지 않는다.
            default -> throw new IllegalArgumentException("Unsupported DROP target: " + type + " " + name);
        };
    }

    /** MySQL: DROP INDEX idx ON users → users. PostgreSQL: DROP INDEX idx → null */
    static String dropIndexTable(Drop drop) {
        List<String> params = drop.getParameters();
        if (params == null) return null;
        for (int i = 0; i + 1 < params.size(); i++) {
            if (params.get(i).equalsIgnoreCase("ON")) return ident(params.get(i + 1));
        }
        return null;
    }

    /** CREATE UNIQUE INDEX 는 UNIQUE 추가다 (전에는 CREATE INDEX 계열을 전부 무시했다). 일반 인덱스는 스키마 변경이 아니다. */
    private List<SchemaChange> handleCreateIndex(CreateIndex ci) {
        String type = ci.getIndex().getType() == null ? "" : ci.getIndex().getType().toUpperCase();
        if (!type.contains("UNIQUE")) return List.of();
        List<String> cols = IndexCatalog.columns(ci.getIndex());
        return List.of(new SchemaChange(ident(ci.getTable().getName()), cols.isEmpty() ? "unknown" : cols.get(0),
                ChangeType.ADD_UNIQUE, RiskLevel.MEDIUM).withIndex(ident(ci.getIndex().getName()), cols));
    }

    /**
     * 이름으로 지우는 인덱스·제약을 이전 마이그레이션의 정의로 분류한다.
     *
     *   UNIQUE / PRIMARY KEY → DROP_UNIQUE (HIGH, 쓰기 경로): 중복 저장이 오류 없이 성공한다
     *   일반 인덱스          → DROP_INDEX  (LOW, 영향 없음): 쿼리는 그대로 동작한다
     *   FK 제약              → DROP_FOREIGN_KEY (LOW)
     *   정의 모름            → DROP_INDEX + 컬럼 null (HIGH). ChangeClassifier 가 엔티티 선언으로 마저 분류하고,
     *                         그래도 모르면 UNIQUE 로 본다.
     *   테이블도 모름        → 예외 (분석 불완전, 종료 코드 3)
     */
    private SchemaChange indexDrop(String table, String name) {
        IndexDef def;
        if (table != null) {
            def = catalog.find(table, name).orElse(null);
        } else {
            List<IndexDef> found = catalog.findByName(name);
            if (found.size() > 1)
                throw new IllegalArgumentException("DROP INDEX " + name + ": 같은 이름의 인덱스가 여러 테이블에 있어 대상을 알 수 없음 "
                        + found.stream().map(IndexDef::table).toList());
            if (found.isEmpty())
                throw new IllegalArgumentException("DROP INDEX " + name + ": 어느 테이블의 인덱스인지 알 수 없음 "
                        + "(ON <table> 이 없고 이전 마이그레이션에서도 정의를 찾지 못함)");
            def = found.get(0);
            table = def.table();
        }

        if (def == null) {
            return new SchemaChange(table, null, ChangeType.DROP_INDEX, RiskLevel.HIGH).withIndex(name, null);
        }
        String cols = String.join(", ", def.columns());
        return switch (def.kind()) {
            case FOREIGN_KEY -> new SchemaChange(table, null, null, ChangeType.DROP_FOREIGN_KEY, RiskLevel.LOW,
                    name, null, null);
            case UNIQUE, PRIMARY_KEY -> dropUnique(table, name, def.columns(),
                    (def.kind() == IndexDef.Kind.PRIMARY_KEY ? "PRIMARY KEY" : "UNIQUE") + " (" + cols + ") 삭제 (정의: " + def.origin() + ")");
            case INDEX -> new SchemaChange(table, def.columns().isEmpty() ? null : def.columns().get(0),
                    ChangeType.DROP_INDEX, RiskLevel.LOW).withIndex(name, def.columns())
                    .reclassify(ChangeType.DROP_INDEX, RiskLevel.LOW,
                            "일반 인덱스 (" + cols + ") 삭제 (정의: " + def.origin() + "): 쿼리는 깨지지 않고 조회 성능만 영향");
        };
    }

    private static SchemaChange dropUnique(String table, String name, List<String> cols, String what) {
        return new SchemaChange(table, cols.isEmpty() ? null : cols.get(0), ChangeType.DROP_UNIQUE, RiskLevel.HIGH)
                .withIndex(name, cols)
                .reclassify(ChangeType.DROP_UNIQUE, RiskLevel.HIGH,
                        what + ": 같은 값을 저장해도 오류 없이 성공함. 이 제약으로 중복을 막던 저장 경로 확인 필요");
    }

    /**
     * 같은 파일에서 UNIQUE 를 지우고 같은 컬럼 집합에 다시 만들면(인덱스 이름 변경 등) 중복을 막는 제약은 그대로다.
     * 컬럼 집합이 다르면 의미가 달라지므로 그대로 둔다.
     */
    private static void cancelUniqueRecreate(List<SchemaChange> changes) {
        for (SchemaChange drop : List.copyOf(changes)) {
            if (drop.getChangeType() != ChangeType.DROP_UNIQUE || drop.getIndexColumns() == null
                    || drop.getIndexColumns().isEmpty()) continue;
            IndexDef dropped = new IndexDef(drop.getTableName(), null, null, drop.getIndexColumns(), IndexDef.Kind.UNIQUE, null);
            changes.stream()
                    .filter(c -> c.getChangeType() == ChangeType.ADD_UNIQUE
                            && drop.getTableName().equals(c.getTableName())
                            && dropped.sameColumnsAs(c.getIndexColumns()))
                    .findFirst()
                    .ifPresent(add -> {
                        changes.remove(add);
                        changes.set(changes.indexOf(drop), drop.reclassify(ChangeType.MODIFY_COMPATIBLE, RiskLevel.LOW,
                                "UNIQUE (" + String.join(", ", drop.getIndexColumns()) + ") 를 같은 파일에서 "
                                        + (add.getIndexName() != null ? add.getIndexName() + " 로 " : "") + "다시 만듦: 중복을 막는 제약은 그대로"));
                    });
        }
    }

    /** ADD UNIQUE 의 대상 컬럼. ADD UNIQUE [INDEX name] (a), ADD CONSTRAINT name UNIQUE (a, b), ADD COLUMN x ... UNIQUE */
    private static List<String> uniqueColumns(AlterExpression expr) {
        if (expr.getUkColumns() != null) return expr.getUkColumns().stream().map(SqlMigrationParser::ident).toList();
        List<String> fromIndex = IndexCatalog.columns(expr.getIndex());
        if (!fromIndex.isEmpty()) return fromIndex;
        if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty())
            return List.of(ident(expr.getColDataTypeList().get(0).getColumnName()));
        return List.of();
    }

    private static String uniqueName(AlterExpression expr) {
        if (expr.getUkName() != null) return ident(expr.getUkName());
        if (expr.getIndex() != null && expr.getIndex().getName() != null) return ident(expr.getIndex().getName());
        return null;
    }

    private boolean isFkColumn(String columnName) {
        return FK_COLUMN_SUFFIX.matcher(columnName).matches();
    }

    private static SchemaChange.ColumnDef columnDef(AlterExpression.ColumnDataType cdt) {
        String specs = cdt.getColumnSpecs() == null ? "" : String.join(" ", cdt.getColumnSpecs()).toUpperCase();
        boolean notNull = (" " + specs.replaceAll("\\s+", " ") + " ").contains(" NOT NULL ");
        return new SchemaChange.ColumnDef(
                cdt.getColDataType().getDataType(),
                cdt.getColDataType().getArgumentsStringList(),
                notNull,
                specs.contains("DEFAULT"));
    }

    private String extractFirstColumnName(AlterExpression expr) {
        if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
            return ident(expr.getColDataTypeList()
                    .get(0)
                    .getColumnName());
        }
        // ADD CONSTRAINT uq UNIQUE (a, b) 처럼 인덱스로 온 경우 첫 컬럼
        if (expr.getIndex() != null && expr.getIndex().getColumnsNames() != null
                && !expr.getIndex().getColumnsNames().isEmpty()) {
            return ident(expr.getIndex().getColumnsNames().get(0));
        }

        return "unknown";
    }

    /** `db`.`users` → users. 인용부호를 벗기고 스키마 접두어를 떼어 소문자로 만든다. */
    static String ident(String raw) {
        String s = raw.replaceAll("[`\"\\[\\]]", "").trim();
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase();
    }

    /** FK 컬럼 목록 "(`a`, `b`)" 안쪽 → "a, b" */
    private static String columnList(String raw) {
        return raw.replaceAll("[`\"\\[\\]]", "").trim().toLowerCase();
    }

    private static String firstLine(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.strip().lines().findFirst().orElse(msg);
    }
}
