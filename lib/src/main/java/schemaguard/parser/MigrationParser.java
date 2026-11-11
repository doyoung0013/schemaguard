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
import net.sf.jsqlparser.statement.drop.Drop;

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
 * DROP FOREIGN KEY / ADD FOREIGN KEY 구문은 정규식으로 먼저 처리한다.
 */
public class MigrationParser {

    /** 식별자: users, `users`, "users", db.users, `db`.`users` */
    private static final String IDENT = "([`\"\\w.]+)";

    /** ALTER TABLE t DROP FOREIGN KEY fk_name */
    private static final Pattern DROP_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + IDENT + "\\s+DROP\\s+FOREIGN\\s+KEY\\s+" + IDENT + "\\s*",
            Pattern.CASE_INSENSITIVE
    );

    /** ALTER TABLE t ADD [CONSTRAINT name] FOREIGN KEY (col) REFERENCES ref(rc) [ON DELETE|UPDATE ...] */
    private static final Pattern ADD_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + IDENT + "\\s+ADD\\s+(?:CONSTRAINT\\s+" + IDENT + "\\s+)?" +
                    "FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+" + IDENT + "\\s*\\(([^)]+)\\)" +
                    "(?:\\s+ON\\s+(?:DELETE|UPDATE)\\s+" +
                    "(?:CASCADE|RESTRICT|SET\\s+NULL|SET\\s+DEFAULT|NO\\s+ACTION))*\\s*",
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

    public ParseResult parse(File sqlFile) throws Exception {
        return parseSQL(Files.readString(sqlFile.toPath()));
    }

    public ParseResult parseSQL(String sql) {
        List<SchemaChange> changes = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();
        List<SqlStatement> statements = splitStatements(sql);

        for (SqlStatement stmt : statements) {
            try {
                changes.addAll(parseStatement(stmt.text()));
            } catch (Exception e) {
                failures.add(new ParseFailure(stmt.line(), stmt.text(), firstLine(e)));
            }
        }

        promoteFkModify(changes);

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
                    RiskLevel.HIGH,
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

        Statement stmt = CCJSqlParserUtil.parse(sql);
        if (stmt instanceof Alter alter) {
            return handleAlter(alter);
        }
        if (stmt instanceof Drop drop) {
            return List.of(handleDrop(drop));
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
                                RiskLevel.HIGH,
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
                    }
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
                        result.add(new SchemaChange(table, col, ChangeType.MODIFY_TYPE, RiskLevel.HIGH));
                    }
                }

                case "MODIFY", "CHANGE" -> {
                    if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                        String col = ident(expr.getColDataTypeList()
                                .get(0)
                                .getColumnName());

                        result.add(new SchemaChange(
                                table,
                                col,
                                ChangeType.MODIFY_TYPE,
                                RiskLevel.HIGH
                        ));
                    }
                }

                case "ADD" -> {
                    if (exprStr.contains("UNIQUE")) {
                        result.add(new SchemaChange(
                                table,
                                extractFirstColumnName(expr),
                                ChangeType.ADD_UNIQUE,
                                RiskLevel.MEDIUM
                        ));
                    } else if (exprStr.contains("NOT NULL")) {
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

    private SchemaChange handleDrop(Drop drop) {
        return new SchemaChange(
                ident(drop.getName().getName()),
                null,
                ChangeType.DROP_TABLE,
                RiskLevel.HIGH
        );
    }

    private boolean isFkColumn(String columnName) {
        return FK_COLUMN_SUFFIX.matcher(columnName).matches();
    }

    private String extractFirstColumnName(AlterExpression expr) {
        if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
            return ident(expr.getColDataTypeList()
                    .get(0)
                    .getColumnName());
        }

        return "unknown";
    }

    /** `db`.`users` → users. 인용부호를 벗기고 스키마 접두어를 떼어 소문자로 만든다. */
    private static String ident(String raw) {
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
