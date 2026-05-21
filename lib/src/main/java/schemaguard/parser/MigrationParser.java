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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 마이그레이션 파일을 파싱하여 SchemaChange 목록을 추출한다.
 *
 * JSQLParser가 일부 MySQL FK DDL 문법을 파싱하지 못하는 문제를 보완하기 위해
 * DROP FOREIGN KEY / ADD FOREIGN KEY 구문은 정규식으로 먼저 추출한다.
 */
public class MigrationParser {

    /** ALTER TABLE t DROP FOREIGN KEY fk_name */
    private static final Pattern DROP_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+DROP\\s+FOREIGN\\s+KEY\\s+(\\w+)",
            Pattern.CASE_INSENSITIVE
    );

    /** ALTER TABLE t ADD [CONSTRAINT name] FOREIGN KEY (col) REFERENCES ref(rc) */
    private static final Pattern ADD_FK_PATTERN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+(?:CONSTRAINT\\s+(\\w+)\\s+)?" +
                    "FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+(\\w+)\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE
    );

    /** FK 역할 컬럼명 판단: suffix가 _id, _fk, _key */
    private static final Pattern FK_COLUMN_SUFFIX =
            Pattern.compile(".*_(id|fk|key)$", Pattern.CASE_INSENSITIVE);

    public List<SchemaChange> parse(File sqlFile) throws Exception {
        return parseSQL(Files.readString(sqlFile.toPath()));
    }

    public List<SchemaChange> parseSQL(String sql) {
        List<SchemaChange> changes = new ArrayList<>();

        String sanitized = extractFkChanges(sql, changes);

        sanitized = sanitized.replaceAll(";\\s*;", ";").trim();

        if (!sanitized.isEmpty() && !sanitized.equals(";")) {
            if (!sanitized.endsWith(";")) {
                sanitized += ";";
            }

            changes.addAll(parseBySqlParser(sanitized));
        }

        return changes;
    }

    /**
     * DROP FOREIGN KEY / ADD FOREIGN KEY 구문을 정규식으로 추출한 뒤
     * 원본 SQL에서 해당 구문을 제거한 문자열을 반환한다.
     */
    private String extractFkChanges(String sql, List<SchemaChange> out) {
        String remaining = sql;

        // DROP FOREIGN KEY fk_name
        Matcher dropMatcher = DROP_FK_PATTERN.matcher(remaining);
        while (dropMatcher.find()) {
            String table = dropMatcher.group(1).toLowerCase();
            String fkName = dropMatcher.group(2).toLowerCase();

            out.add(new SchemaChange(
                    table,
                    null,
                    null,
                    ChangeType.DROP_FOREIGN_KEY,
                    RiskLevel.HIGH,
                    fkName,
                    null,
                    null
            ));

            remaining = removeSqlStatement(remaining, dropMatcher.group(0));

            // remaining이 바뀌었으므로 Matcher 재생성
            dropMatcher = DROP_FK_PATTERN.matcher(remaining);
        }

        // ADD FOREIGN KEY (col) REFERENCES ref(rc)
        Matcher addMatcher = ADD_FK_PATTERN.matcher(remaining);
        while (addMatcher.find()) {
            String table = addMatcher.group(1).toLowerCase();
            String constraint = addMatcher.group(2) != null
                    ? addMatcher.group(2).toLowerCase()
                    : null;
            String column = addMatcher.group(3).trim().toLowerCase();
            String refTable = addMatcher.group(4).toLowerCase();
            String refColumn = addMatcher.group(5).trim().toLowerCase();

            out.add(new SchemaChange(
                    table,
                    column,
                    null,
                    ChangeType.ADD_FOREIGN_KEY,
                    RiskLevel.MEDIUM,
                    constraint,
                    refTable,
                    refColumn
            ));

            remaining = removeSqlStatement(remaining, addMatcher.group(0));

            // remaining이 바뀌었으므로 Matcher 재생성
            addMatcher = ADD_FK_PATTERN.matcher(remaining);
        }

        promoteFkModify(out);

        return remaining;
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

    /**
     * SQL에서 특정 구문과 뒤따르는 세미콜론을 제거한다.
     */
    private String removeSqlStatement(String sql, String statement) {
        return sql.replaceFirst(Pattern.quote(statement) + "\\s*;?", "").trim();
    }

    private List<SchemaChange> parseBySqlParser(String sql) {
        List<SchemaChange> result = new ArrayList<>();

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);

            for (Statement stmt : statements.getStatements()) {
                if (stmt instanceof Alter alter) {
                    result.addAll(handleAlter(alter));
                } else if (stmt instanceof Drop drop) {
                    result.add(handleDrop(drop));
                }
            }
        } catch (Exception e) {
            System.err.println("  [WARN] JSQLParser failed to parse a statement: " + e.getMessage());
        }

        return result;
    }

    private List<SchemaChange> handleAlter(Alter alter) {
        List<SchemaChange> result = new ArrayList<>();
        String table = alter.getTable().getName().toLowerCase();

        for (AlterExpression expr : alter.getAlterExpressions()) {
            String operation = expr.getOperation().name().toUpperCase();
            String exprStr = expr.toString().toUpperCase();

            switch (operation) {
                case "DROP" -> {
                    if (expr.getColumnName() != null) {
                        String col = expr.getColumnName().toLowerCase();

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
                                expr.getColumnOldName().toLowerCase(),
                                expr.getColumnName().toLowerCase(),
                                ChangeType.RENAME_COLUMN,
                                RiskLevel.HIGH
                        ));
                    }
                }

                case "MODIFY", "CHANGE" -> {
                    if (expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                        String col = expr.getColDataTypeList()
                                .get(0)
                                .getColumnName()
                                .toLowerCase();

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
                        String col = expr.getColDataTypeList()
                                .get(0)
                                .getColumnName()
                                .toLowerCase();

                        result.add(new SchemaChange(
                                table,
                                col,
                                ChangeType.ADD_COLUMN,
                                RiskLevel.LOW
                        ));
                    }
                }

                default -> {
                    // 무시
                }
            }
        }

        return result;
    }

    private SchemaChange handleDrop(Drop drop) {
        return new SchemaChange(
                drop.getName().getName().toLowerCase(),
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
            return expr.getColDataTypeList()
                    .get(0)
                    .getColumnName()
                    .toLowerCase();
        }

        return "unknown";
    }
}