package schemaguard.model;

/**
 * SQL 마이그레이션 파일에서 추출한 단일 스키마 변경 정보.
 * SqlMigrationParser가 생성하고, ImpactAnalyzer가 소비한다.
 */
public class SchemaChange {

	private final String tableName;
    private final String columnName;      // 컬럼 레벨 변경이 없으면 null
    private final String newName;         // RENAME 시 변경 후 이름 (없으면 null)
    private final ChangeType changeType;
    private final RiskLevel riskLevel;
 
    // FK 전용 추가 정보
    private final String fkName;            // ex. "fk_post_author"  (없으면 null)
    private final String referencedTable;   // ex. "users"           (없으면 null)
    private final String referencedColumn;  // ex. "id"              (없으면 null)

    // 컬럼 정의 (MODIFY / ALTER TYPE / ADD COLUMN 일 때만, 없으면 null)
    private ColumnDef columnDef;
    // 재분류 근거 등 사람이 읽을 설명 (없으면 null)
    private String note;
    // 인덱스·제약 변경일 때만. indexColumns 가 null 이면 정의를 찾지 못한 것이다 (어떤 컬럼인지, UNIQUE 인지 모름).
    private String indexName;
    private java.util.List<String> indexColumns;

    /**
     * SQL 컬럼 정의. 예) VARCHAR(50) NOT NULL → dataType "VARCHAR", args [50], notNull true
     */
    public record ColumnDef(String dataType, java.util.List<String> args, boolean notNull, boolean hasDefault) {
        public ColumnDef {
            dataType = dataType == null ? "" : dataType.toUpperCase();
            args = args == null ? java.util.List.of() : java.util.List.copyOf(args);
        }
        public Integer firstArgAsInt() {
            if (args.isEmpty()) return null;
            try { return Integer.parseInt(args.get(0).trim()); } catch (NumberFormatException e) { return null; }
        }
        @Override public String toString() {
            return dataType + (args.isEmpty() ? "" : "(" + String.join(",", args) + ")") + (notNull ? " NOT NULL" : "");
        }
    }
 
    // ── 기본 생성자 (non-FK) ──────────────────────────────────────────────────
 
    public SchemaChange(String tableName, String columnName,
                        ChangeType changeType, RiskLevel riskLevel) {
        this(tableName, columnName, null, changeType, riskLevel, null, null, null);
    }
 
    public SchemaChange(String tableName, String columnName, String newName,
                        ChangeType changeType, RiskLevel riskLevel) {
        this(tableName, columnName, newName, changeType, riskLevel, null, null, null);
    }
 
    // ── FK 생성자 ─────────────────────────────────────────────────────────────
 
    public SchemaChange(String tableName, String columnName, String newName,
                        ChangeType changeType, RiskLevel riskLevel,
                        String fkName, String referencedTable, String referencedColumn) {
        this.tableName        = tableName;
        this.columnName       = columnName;
        this.newName          = newName;
        this.changeType       = changeType;
        this.riskLevel        = riskLevel;
        this.fkName           = fkName;
        this.referencedTable  = referencedTable;
        this.referencedColumn = referencedColumn;
    }
 
    public String getTableName()         { return tableName; }
    public String getColumnName()        { return columnName; }
    public String getNewName()           { return newName; }
    public ChangeType getChangeType()    { return changeType; }
    public RiskLevel getRiskLevel()      { return riskLevel; }
    public String getFkName()            { return fkName; }
    public String getReferencedTable()   { return referencedTable; }
    public String getReferencedColumn()  { return referencedColumn; }
 
    public ColumnDef getColumnDef() { return columnDef; }
    public String getNote()         { return note; }
    public String getIndexName()    { return indexName; }
    public java.util.List<String> getIndexColumns() { return indexColumns; }

    public SchemaChange withColumnDef(ColumnDef def) {
        SchemaChange c = copy(changeType, riskLevel);
        c.columnDef = def;
        return c;
    }

    /** 인덱스·제약 정보를 붙인 사본. columns 가 null 이면 정의를 모른다는 뜻이다. */
    public SchemaChange withIndex(String name, java.util.List<String> columns) {
        SchemaChange c = copy(changeType, riskLevel);
        c.indexName = name;
        c.indexColumns = columns == null ? null : java.util.List.copyOf(columns);
        return c;
    }

    /** 인덱스 정의를 찾아 컬럼과 종류를 정한 사본. 대표 컬럼(첫 컬럼)을 columnName 으로 쓴다. */
    public SchemaChange resolvedIndex(String table, java.util.List<String> columns,
                                      ChangeType type, RiskLevel risk, String note) {
        SchemaChange c = new SchemaChange(table, columns.isEmpty() ? null : columns.get(0), newName, type, risk,
                fkName, referencedTable, referencedColumn);
        c.columnDef = columnDef;
        c.note = note;
        c.indexName = indexName;
        c.indexColumns = java.util.List.copyOf(columns);
        return c;
    }

    /** 같은 대상에 대해 변경 종류/위험도만 바꾼 사본을 만든다 (재분류용). */
    public SchemaChange reclassify(ChangeType type, RiskLevel risk, String note) {
        SchemaChange c = copy(type, risk);
        c.note = note;
        return c;
    }

    private SchemaChange copy(ChangeType type, RiskLevel risk) {
        SchemaChange c = new SchemaChange(tableName, columnName, newName, type, risk,
                fkName, referencedTable, referencedColumn);
        c.columnDef = columnDef;
        c.note = note;
        c.indexName = indexName;
        c.indexColumns = indexColumns;
        return c;
    }

    public boolean isFkChange() {
        return changeType == ChangeType.DROP_FOREIGN_KEY
            || changeType == ChangeType.DROP_FK_COLUMN
            || changeType == ChangeType.MODIFY_FK_REFERENCE
            || changeType == ChangeType.ADD_FOREIGN_KEY;
    }
 
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] %s on %s", riskLevel, changeType, tableName));
        if (columnName != null)      sb.append(".").append(columnName);
        if (fkName != null)          sb.append(" (FK: ").append(fkName).append(")");
        if (indexName != null)       sb.append(" (INDEX: ").append(indexName).append(")");
        if (referencedTable != null) sb.append(" → ref: ").append(referencedTable)
                                       .append(".").append(referencedColumn);
        return sb.toString();
    }
    
    
}