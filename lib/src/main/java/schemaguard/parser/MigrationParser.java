package schemaguard.parser;

import schemaguard.model.ChangeType;
import schemaguard.model.RiskLevel;
import schemaguard.model.SchemaChange;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.drop.Drop;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 마이그레이션 파일을 파싱하여 SchemaChange 목록을 추출한다.
 *
 * 지원 구문:
 *   ALTER TABLE t DROP COLUMN c
 *   ALTER TABLE t RENAME COLUMN c TO c2
 *   ALTER TABLE t MODIFY COLUMN c TYPE
 *   ALTER TABLE t ALTER COLUMN c SET NOT NULL
 *   ALTER TABLE t ADD UNIQUE (c)
 *   DROP TABLE t
 */
public class MigrationParser {

    public List<SchemaChange> parse(File sqlFile) throws Exception {
        String sql = Files.readString(sqlFile.toPath());
        return parseSQL(sql);
    }

    public List<SchemaChange> parseSQL(String sql) throws Exception {
        List<SchemaChange> changes = new ArrayList<>();

        // 세미콜론이 없는 단일 구문도 처리하기 위해 끝에 붙여준다
        String normalized = sql.trim();
        if (!normalized.endsWith(";")) normalized += ";";

        Statements statements = CCJSqlParserUtil.parseStatements(normalized);

        for (Statement stmt : statements.getStatements()) {
            if (stmt instanceof Alter) {
                changes.addAll(handleAlter((Alter) stmt));
            } else if (stmt instanceof Drop) {
                changes.add(handleDrop((Drop) stmt));
            }
        }
        return changes;
    }

    // ── ALTER TABLE 처리 ─────────────────────────────────────────────────────

    private List<SchemaChange> handleAlter(Alter alter) {
        List<SchemaChange> result = new ArrayList<>();
        String table = alter.getTable().getName().toLowerCase();

        for (AlterExpression expr : alter.getAlterExpressions()) {
            String operation = expr.getOperation().name().toUpperCase();

            switch (operation) {
                case "DROP" -> {
                    // ALTER TABLE t DROP COLUMN c
                    if (expr.getColumnName() != null) {
                        result.add(new SchemaChange(
                                table, expr.getColumnName().toLowerCase(),
                                ChangeType.DROP_COLUMN, RiskLevel.HIGH));
                    }
                }
                case "RENAME" -> {
                    // ALTER TABLE t RENAME COLUMN c TO c2
                    if (expr.getColumnName() != null && expr.getColumnOldName() != null) {
                        result.add(new SchemaChange(
                                table,
                                expr.getColumnOldName().toLowerCase(),
                                expr.getColumnName().toLowerCase(),
                                ChangeType.RENAME_COLUMN, RiskLevel.HIGH));
                    }
                }
                case "MODIFY", "CHANGE" -> {
                    // ALTER TABLE t MODIFY COLUMN c INT
                    if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                        String col = expr.getColDataTypeList().get(0).getColumnName().toLowerCase();
                        result.add(new SchemaChange(
                                table, col, ChangeType.MODIFY_TYPE, RiskLevel.HIGH));
                    }
                }
                case "ADD" -> {
                    // ADD UNIQUE / ADD NOT NULL 등 제약 조건 추가 감지
                    String exprStr = expr.toString().toUpperCase();
                    if (exprStr.contains("UNIQUE")) {
                        String col = extractFirstColumnName(expr);
                        result.add(new SchemaChange(
                                table, col, ChangeType.ADD_UNIQUE, RiskLevel.MEDIUM));
                    } else if (exprStr.contains("NOT NULL")) {
                        String col = extractFirstColumnName(expr);
                        result.add(new SchemaChange(
                                table, col, ChangeType.ADD_NOT_NULL, RiskLevel.MEDIUM));
                    }
                    // 단순 컬럼 추가는 LOW → 리포트에는 포함하되 경고는 생략 가능
                    else if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                        String col = expr.getColDataTypeList().get(0).getColumnName().toLowerCase();
                        result.add(new SchemaChange(
                                table, col, ChangeType.ADD_COLUMN, RiskLevel.LOW));
                    }
                }
                default -> { /* 그 외 ALTER 구문은 무시 */ }
            }
        }
        return result;
    }

    // ── DROP TABLE 처리 ──────────────────────────────────────────────────────

    private SchemaChange handleDrop(Drop drop) {
        return new SchemaChange(
                drop.getName().getName().toLowerCase(),
                null,
                ChangeType.DROP_TABLE, RiskLevel.HIGH);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private String extractFirstColumnName(AlterExpression expr) {
        if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
            return expr.getColDataTypeList().get(0).getColumnName().toLowerCase();
        }
        return "unknown";
    }
}