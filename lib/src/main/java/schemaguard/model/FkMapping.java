package schemaguard.model;

public class FkMapping {

    private final String entityClass;
    private final String fieldName;
    private final String tableName;
    private final String joinColumnName;
    private final String referencedEntity;
    private final String fkRelationType;
    private final String filePath;    // 소스 파일 경로
    private final int    lineNumber;  // 필드 선언 라인

    public FkMapping(String entityClass, String fieldName, String tableName,
                     String joinColumnName, String referencedEntity, String fkRelationType) {
        this(entityClass, fieldName, tableName, joinColumnName,
             referencedEntity, fkRelationType, null, -1);
    }

    public FkMapping(String entityClass, String fieldName, String tableName,
                     String joinColumnName, String referencedEntity, String fkRelationType,
                     String filePath, int lineNumber) {
        this.entityClass      = entityClass;
        this.fieldName        = fieldName;
        this.tableName        = tableName;
        this.joinColumnName   = joinColumnName;
        this.referencedEntity = referencedEntity;
        this.fkRelationType   = fkRelationType;
        this.filePath         = filePath;
        this.lineNumber       = lineNumber;
    }

    public String getEntityClass()      { return entityClass; }
    public String getFieldName()        { return fieldName; }
    public String getTableName()        { return tableName; }
    public String getJoinColumnName()   { return joinColumnName; }
    public String getReferencedEntity() { return referencedEntity; }
    public String getFkRelationType()   { return fkRelationType; }
    public String getFilePath()         { return filePath; }
    public int    getLineNumber()       { return lineNumber; }

    public String getFkFieldNodeId() { return "fkfield:" + entityClass + "." + fieldName; }
    public String getColumnNodeId()  { return "col:"     + tableName   + "." + joinColumnName; }

    @Override
    public String toString() {
        return String.format("%s.%s (@%s) -> %s [col: %s.%s]",
                entityClass, fieldName, fkRelationType,
                referencedEntity, tableName, joinColumnName);
    }
}