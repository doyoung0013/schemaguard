package schemaguard.model;

/**
 * Entity 클래스에서 추출한 Java 필드 ↔ DB 컬럼 매핑 정보.
 *
 * ex. User.email  ↔  users.email
 */
public class EntityMapping {

    private final String entityClass;   // ex. "User"
    private final String fieldName;     // ex. "email"
    private final String tableName;     // ex. "users"
    private final String columnName;    // ex. "email"  (@Column(name=...) 우선, 없으면 필드명 snake_case 변환)

    public EntityMapping(String entityClass, String fieldName,
                         String tableName, String columnName) {
        this.entityClass = entityClass;
        this.fieldName   = fieldName;
        this.tableName   = tableName;
        this.columnName  = columnName;
    }

    public String getEntityClass() { return entityClass; }
    public String getFieldName()   { return fieldName; }
    public String getTableName()   { return tableName; }
    public String getColumnName()  { return columnName; }

    /** 그래프 노드 ID: FIELD 타입 */
    public String getFieldNodeId()  { return "field:" + entityClass + "." + fieldName; }

    /** 그래프 노드 ID: COLUMN 타입 */
    public String getColumnNodeId() { return "col:" + tableName + "." + columnName; }

    @Override
    public String toString() {
        return entityClass + "." + fieldName + " <-> " + tableName + "." + columnName;
    }
}
