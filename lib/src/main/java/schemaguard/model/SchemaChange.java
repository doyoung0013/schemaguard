package schemaguard.model;

/**
 * SQL 마이그레이션 파일에서 추출한 단일 스키마 변경 정보.
 * MigrationParser가 생성하고, ImpactAnalyzer가 소비한다.
 */
public class SchemaChange {

    private final String tableName;    // ex. "users"
    private final String columnName;   // ex. "email"  (테이블 레벨 변경이면 null)
    private final String newName;      // RENAME 시 변경 후 이름 (해당 없으면 null)
    private final ChangeType changeType;
    private final RiskLevel riskLevel;

    public SchemaChange(String tableName, String columnName, ChangeType changeType, RiskLevel riskLevel) {
        this(tableName, columnName, null, changeType, riskLevel);
    }

    public SchemaChange(String tableName, String columnName, String newName,
                        ChangeType changeType, RiskLevel riskLevel) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.newName = newName;
        this.changeType = changeType;
        this.riskLevel = riskLevel;
    }

    public String getTableName()    { return tableName; }
    public String getColumnName()   { return columnName; }
    public String getNewName()      { return newName; }
    public ChangeType getChangeType() { return changeType; }
    public RiskLevel getRiskLevel() { return riskLevel; }

    @Override
    public String toString() {
        return String.format("[%s] %s on %s.%s",
                riskLevel, changeType, tableName,
                columnName != null ? columnName : "(table)");
    }
}