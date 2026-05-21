package schemaguard.model;

/**
 * SQL 마이그레이션 파일에서 추출한 단일 스키마 변경 정보.
 * MigrationParser가 생성하고, ImpactAnalyzer가 소비한다.
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
        if (referencedTable != null) sb.append(" → ref: ").append(referencedTable)
                                       .append(".").append(referencedColumn);
        return sb.toString();
    }
    
    
}