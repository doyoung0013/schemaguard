package schemaguard.parser;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import schemaguard.model.IndexDef;
import schemaguard.model.IndexDef.Kind;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;

/**
 * 테이블별 인덱스·제약 목록. 마이그레이션 구문을 순서대로 적용해 "그 시점의 스키마"에 어떤 인덱스가 있는지 기억한다.
 *
 * DROP INDEX 구문에는 인덱스 이름만 있고 컬럼도, UNIQUE 인지도 없다. 그래서 이전 마이그레이션(V1__init.sql 의
 * CREATE TABLE ... UNIQUE, CREATE UNIQUE INDEX 등)을 차례로 적용해 정의를 알아 둔다.
 *
 * 이전 마이그레이션은 이미 적용이 끝난 이력이라, 읽지 못한 구문이 있어도 실패로 보지 않고 개수만 센다.
 * (그 구문이 만든 인덱스를 모르면 삭제 시점에 "정의 모름"으로 보수적으로 분류된다.)
 */
public class IndexCatalog {

    private final Map<String, List<IndexDef>> byTable = new TreeMap<>();
    private int appliedStatements;
    private int failedStatements;
    private int files;

    /** Flyway 버전 순서로 정렬된 이전 마이그레이션 파일을 적용한다. 읽지 못하는 파일은 건너뛴다. */
    public static IndexCatalog load(List<File> history) {
        IndexCatalog c = new IndexCatalog();
        for (File f : history) {
            try {
                c.applyAll(Files.readString(f.toPath()), f.getName());
                c.files++;
            } catch (Exception e) {
                System.err.println("  [WARN] Could not read migration history file: " + f.getName() + " (" + e.getMessage() + ")");
            }
        }
        return c;
    }

    public IndexCatalog copy() {
        IndexCatalog c = new IndexCatalog();
        byTable.forEach((t, defs) -> c.byTable.put(t, new ArrayList<>(defs)));
        c.appliedStatements = appliedStatements;
        c.failedStatements = failedStatements;
        c.files = files;
        return c;
    }

    // ── 조회 ─────────────────────────────────────────────────────────────────

    public Optional<IndexDef> find(String table, String indexName) {
        return byTable.getOrDefault(lower(table), List.of()).stream()
                .filter(d -> d.answersTo(indexName)).findFirst();
    }

    /** 테이블을 모를 때 (PostgreSQL 의 DROP INDEX name). 이름이 같은 인덱스가 여러 테이블에 있으면 여러 개다. */
    public List<IndexDef> findByName(String indexName) {
        List<IndexDef> out = new ArrayList<>();
        byTable.values().forEach(defs -> defs.stream().filter(d -> d.answersTo(indexName)).forEach(out::add));
        return out;
    }

    public Optional<IndexDef> primaryKey(String table) {
        return byTable.getOrDefault(lower(table), List.of()).stream()
                .filter(d -> d.kind() == Kind.PRIMARY_KEY).findFirst();
    }

    public int size()              { return byTable.values().stream().mapToInt(List::size).sum(); }
    public int files()             { return files; }
    public int appliedStatements() { return appliedStatements; }
    public int failedStatements()  { return failedStatements; }

    // ── 적용 ─────────────────────────────────────────────────────────────────

    public void applyAll(String sql) {
        applyAll(sql, "migration history");
    }

    private void applyAll(String sql, String origin) {
        for (String stmt : SqlMigrationParser.splitStatementTexts(sql)) {
            if (apply(stmt, origin)) appliedStatements++;
            else failedStatements++;
        }
    }

    /** 구문 하나를 적용한다. 읽지 못하면 false. 인덱스와 관계없는 구문(INSERT 등)은 아무것도 바꾸지 않고 true. */
    public boolean apply(String sql, String origin) {
        try {
            Matcher dropFk = SqlMigrationParser.DROP_FK_PATTERN.matcher(sql);
            if (dropFk.matches()) {
                remove(SqlMigrationParser.ident(dropFk.group(1)), dropFk.group(2));
                return true;
            }
            Matcher addFk = SqlMigrationParser.ADD_FK_PATTERN.matcher(sql);
            if (addFk.matches()) {
                String table = SqlMigrationParser.ident(addFk.group(1));
                String name = addFk.group(2) != null ? SqlMigrationParser.ident(addFk.group(2)) : null;
                add(new IndexDef(table, name, List.of(), splitColumns(addFk.group(3)), Kind.FOREIGN_KEY, origin));
                return true;
            }
            Matcher dropKey = SqlMigrationParser.DROP_INDEX_ALTER_PATTERN.matcher(sql);
            if (dropKey.matches()) {
                remove(SqlMigrationParser.ident(dropKey.group(1)), dropKey.group(2));
                return true;
            }

            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof CreateTable ct)               applyCreateTable(ct, origin);
            else if (stmt instanceof CreateIndex ci)          applyCreateIndex(ci, origin);
            else if (stmt instanceof Alter alter)             applyAlter(alter, origin);
            else if (stmt instanceof Drop drop)               applyDrop(drop);
            else if (stmt instanceof RenameTableStatement rn) {
                for (Map.Entry<Table, Table> e : rn.getTableNames())
                    renameTable(SqlMigrationParser.ident(e.getKey().getName()), SqlMigrationParser.ident(e.getValue().getName()));
            }
            return true;
        } catch (Exception | Error e) {   // JSQLParser 는 일부 입력에 TokenMgrError 를 던진다
            return false;
        }
    }

    private void applyCreateTable(CreateTable ct, String origin) {
        String table = SqlMigrationParser.ident(ct.getTable().getName());
        byTable.remove(table);   // CREATE TABLE 은 새 테이블이다 (같은 이름을 지우고 다시 만든 경우)
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                String spec = cd.getColumnSpecs() == null ? "" : " " + String.join(" ", cd.getColumnSpecs()).toUpperCase() + " ";
                String col = SqlMigrationParser.ident(cd.getColumnName());
                if (spec.contains(" PRIMARY KEY ")) add(primaryKey(table, List.of(col), origin));
                else if (spec.contains(" UNIQUE ")) add(implicitUnique(table, List.of(col), origin));
            }
        }
        if (ct.getIndexes() != null) {
            for (Index idx : ct.getIndexes()) addIndex(table, idx.getType(), idx.getName(), columns(idx), origin);
        }
    }

    private void applyCreateIndex(CreateIndex ci, String origin) {
        String table = SqlMigrationParser.ident(ci.getTable().getName());
        Index idx = ci.getIndex();
        boolean unique = idx.getType() != null && idx.getType().toUpperCase().contains("UNIQUE");
        add(new IndexDef(table, SqlMigrationParser.ident(idx.getName()), List.of(), columns(idx),
                unique ? Kind.UNIQUE : Kind.INDEX, origin));
    }

    private void applyAlter(Alter alter, String origin) {
        String table = SqlMigrationParser.ident(alter.getTable().getName());
        for (AlterExpression e : alter.getAlterExpressions()) {
            switch (e.getOperation().name()) {
                case "ADD" -> {
                    if (e.getUkColumns() != null) {
                        List<String> cols = e.getUkColumns().stream().map(SqlMigrationParser::ident).toList();
                        add(e.getUkName() != null
                                ? new IndexDef(table, SqlMigrationParser.ident(e.getUkName()), List.of(), cols, Kind.UNIQUE, origin)
                                : implicitUnique(table, cols, origin));
                    } else if (e.getIndex() != null) {
                        addIndex(table, e.getIndex().getType(), e.getIndex().getName(), columns(e.getIndex()), origin);
                    } else if (e.getColDataTypeList() != null) {
                        for (AlterExpression.ColumnDataType cdt : e.getColDataTypeList()) {
                            String spec = cdt.getColumnSpecs() == null ? "" : " " + String.join(" ", cdt.getColumnSpecs()).toUpperCase() + " ";
                            String col = SqlMigrationParser.ident(cdt.getColumnName());
                            if (spec.contains(" PRIMARY KEY ")) add(primaryKey(table, List.of(col), origin));
                            else if (spec.contains(" UNIQUE ")) add(implicitUnique(table, List.of(col), origin));
                        }
                    }
                }
                case "DROP" -> {
                    if (e.getColumnName() != null) dropColumn(table, SqlMigrationParser.ident(e.getColumnName()));
                    else if (e.getIndex() != null && e.getIndex().getName() != null) remove(table, e.getIndex().getName());
                    else if (e.getConstraintName() != null) remove(table, e.getConstraintName());
                }
                case "DROP_PRIMARY_KEY" -> byTable.getOrDefault(table, new ArrayList<>()).removeIf(d -> d.kind() == Kind.PRIMARY_KEY);
                case "DROP_UNIQUE" -> {
                    List<String> cols = e.getPkColumns() == null ? List.of()
                            : e.getPkColumns().stream().map(SqlMigrationParser::ident).toList();
                    byTable.getOrDefault(table, new ArrayList<>()).removeIf(d -> d.kind() == Kind.UNIQUE && d.sameColumnsAs(cols));
                }
                case "RENAME_TABLE" -> {
                    if (e.getNewTableName() != null) renameTable(table, SqlMigrationParser.ident(e.getNewTableName()));
                }
                case "RENAME" -> {
                    if (e.getColumnOldName() != null && e.getColumnName() != null)
                        renameColumn(table, SqlMigrationParser.ident(e.getColumnOldName()), SqlMigrationParser.ident(e.getColumnName()));
                }
                default -> { }
            }
        }
    }

    private void applyDrop(Drop drop) {
        String type = drop.getType() == null ? "" : drop.getType().toUpperCase();
        String name = SqlMigrationParser.ident(drop.getName().getName());
        if (type.equals("TABLE") || type.equals("VIEW")) {
            byTable.remove(name);
        } else if (type.equals("INDEX")) {
            String table = SqlMigrationParser.dropIndexTable(drop);
            if (table != null) remove(table, name);
            else byTable.values().forEach(defs -> defs.removeIf(d -> d.answersTo(name)));
        }
    }

    // ── 변경 연산 ─────────────────────────────────────────────────────────────

    private void add(IndexDef def) {
        List<IndexDef> defs = byTable.computeIfAbsent(def.table(), k -> new ArrayList<>());
        if (def.name() != null) defs.removeIf(d -> def.name().equals(d.name()));
        defs.add(def);
    }

    private void addIndex(String table, String rawType, String rawName, List<String> cols, String origin) {
        String type = rawType == null ? "" : rawType.toUpperCase();
        String name = rawName == null ? null : SqlMigrationParser.ident(rawName);
        if (type.contains("PRIMARY")) {
            add(primaryKey(table, cols, origin));
        } else if (type.contains("FOREIGN")) {
            add(new IndexDef(table, name, List.of(), cols, Kind.FOREIGN_KEY, origin));
        } else if (type.contains("UNIQUE")) {
            add(name != null ? new IndexDef(table, name, List.of(), cols, Kind.UNIQUE, origin)
                             : implicitUnique(table, cols, origin));
        } else if (type.contains("CHECK")) {
            // CHECK 제약은 중복이나 조회와 관계없다
        } else if (!cols.isEmpty()) {
            add(new IndexDef(table, name, name == null ? List.of(cols.get(0)) : List.of(), cols, Kind.INDEX, origin));
        }
    }

    private void remove(String table, String rawName) {
        String name = SqlMigrationParser.ident(rawName);
        byTable.getOrDefault(lower(table), new ArrayList<>()).removeIf(d -> d.answersTo(name));
    }

    /** 컬럼을 지우면 MySQL 은 그 컬럼을 인덱스에서 빼고, 컬럼이 남지 않은 인덱스는 지운다. */
    private void dropColumn(String table, String col) {
        List<IndexDef> defs = byTable.get(table);
        if (defs == null) return;
        List<IndexDef> out = new ArrayList<>();
        for (IndexDef d : defs) {
            if (!d.columns().contains(col)) { out.add(d); continue; }
            List<String> rest = d.columns().stream().filter(c -> !c.equals(col)).toList();
            if (!rest.isEmpty()) out.add(new IndexDef(d.table(), d.name(), d.aliases(), rest, d.kind(), d.origin()));
        }
        byTable.put(table, out);
    }

    private void renameColumn(String table, String from, String to) {
        List<IndexDef> defs = byTable.get(table);
        if (defs == null) return;
        byTable.put(table, new ArrayList<>(defs.stream().map(d -> new IndexDef(d.table(), d.name(), d.aliases(),
                d.columns().stream().map(c -> c.equals(from) ? to : c).toList(), d.kind(), d.origin())).toList()));
    }

    private void renameTable(String from, String to) {
        List<IndexDef> defs = byTable.remove(from);
        if (defs == null) return;
        List<IndexDef> moved = new ArrayList<>();
        for (IndexDef d : defs) moved.add(d.onTable(to));
        byTable.put(to, moved);
    }

    // ── 이름 없는 제약의 DB 이름 후보 ─────────────────────────────────────────

    /** MySQL: 첫 컬럼 이름. PostgreSQL: <table>_<col1>_<col2>_key */
    private static IndexDef implicitUnique(String table, List<String> cols, String origin) {
        List<String> aliases = cols.isEmpty() ? List.of()
                : List.of(cols.get(0), table + "_" + String.join("_", cols) + "_key");
        return new IndexDef(table, null, aliases, cols, Kind.UNIQUE, origin);
    }

    /** MySQL: PRIMARY. PostgreSQL: <table>_pkey */
    private static IndexDef primaryKey(String table, List<String> cols, String origin) {
        return new IndexDef(table, null, List.of("primary", table + "_pkey"), cols, Kind.PRIMARY_KEY, origin);
    }

    static List<String> columns(Index idx) {
        if (idx == null || idx.getColumns() == null) return List.of();   // DROP INDEX 의 Index 는 컬럼이 없다
        return idx.getColumnsNames().stream().map(SqlMigrationParser::ident).toList();
    }

    private static List<String> splitColumns(String raw) {
        return Arrays.stream(raw.split(",")).map(SqlMigrationParser::ident).filter(s -> !s.isEmpty()).toList();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
