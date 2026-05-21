package schemaguard.model;

public class EntityMapping {

    private final String entityClass;
    private final String fieldName;
    private final String tableName;
    private final String columnName;
    private final String filePath;    // 소스 파일 경로 (null 가능)
    private final int    lineNumber;  // 필드 선언 라인 (-1 이면 미정)

    public EntityMapping(String entityClass, String fieldName,
                         String tableName,  String columnName) {
        this(entityClass, fieldName, tableName, columnName, null, -1);
    }

    public EntityMapping(String entityClass, String fieldName,
                         String tableName,  String columnName,
                         String filePath,   int lineNumber) {
        this.entityClass = entityClass;
        this.fieldName   = fieldName;
        this.tableName   = tableName;
        this.columnName  = columnName;
        this.filePath    = filePath;
        this.lineNumber  = lineNumber;
    }

    public String getEntityClass() { return entityClass; }
    public String getFieldName()   { return fieldName; }
    public String getTableName()   { return tableName; }
    public String getColumnName()  { return columnName; }
    public String getFilePath()    { return filePath; }
    public int    getLineNumber()  { return lineNumber; }

    public String getFieldNodeId()  { return "field:" + entityClass + "." + fieldName; }
    public String getColumnNodeId() { return "col:"   + tableName   + "." + columnName; }

    @Override
    public String toString() {
        return entityClass + "." + fieldName + " <-> " + tableName + "." + columnName;
    }
}