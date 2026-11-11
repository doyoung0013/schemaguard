package schemaguard.model;

public class EntityMapping {

    private final String entityClass;
    private final String fieldName;
    private final String tableName;
    private final String columnName;
    private final String filePath;    // 소스 파일 경로 (null 가능)
    private final int    lineNumber;  // 필드 선언 라인 (-1 이면 미정)
    private final String  javaType;   // 필드 Java 타입 (@Enumerated STRING 이면 "String", ORDINAL 이면 "Integer"), 모르면 null
    private final Integer length;     // @Column(length), 지정 안 했으면 null (JPA 기본 255)
    private final boolean nullable;   // @Column(nullable = false) / 기본형 / @Id 이면 false

    public EntityMapping(String entityClass, String fieldName,
                         String tableName,  String columnName) {
        this(entityClass, fieldName, tableName, columnName, null, -1);
    }

    public EntityMapping(String entityClass, String fieldName,
                         String tableName,  String columnName,
                         String filePath,   int lineNumber) {
        this(entityClass, fieldName, tableName, columnName, filePath, lineNumber, null, null, true);
    }

    public EntityMapping(String entityClass, String fieldName,
                         String tableName,  String columnName,
                         String filePath,   int lineNumber,
                         String javaType,   Integer length, boolean nullable) {
        this.javaType    = javaType;
        this.length      = length;
        this.nullable    = nullable;
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
    public String  getJavaType()   { return javaType; }
    public Integer getLength()     { return length; }
    public boolean isNullable()    { return nullable; }

    public String getFieldNodeId()  { return "field:" + entityClass + "." + fieldName; }
    public String getColumnNodeId() { return "col:"   + tableName   + "." + columnName; }

    @Override
    public String toString() {
        return entityClass + "." + fieldName + " <-> " + tableName + "." + columnName;
    }
}