package schemaguard.model;

/**
 * Entity 클래스에서 추출한 FK 연관관계 매핑 정보.
 *
 * 대상 어노테이션: @ManyToOne, @OneToMany, @OneToOne, @JoinColumn
 *
 * ex.
 *   @ManyToOne
 *   @JoinColumn(name = "author_id")
 *   private User author;
 *
 *   → entityClass      = "Post"
 *     fieldName        = "author"
 *     joinColumnName   = "author_id"    (@JoinColumn name 속성)
 *     tableName        = "posts"        (소유 테이블)
 *     referencedEntity = "User"         (필드 타입)
 *     relationType     = "ManyToOne"
 */
public class FkMapping {

    private final String entityClass;        // ex. "Post"
    private final String fieldName;          // ex. "author"
    private final String tableName;          // ex. "posts"
    private final String joinColumnName;     // ex. "author_id"  (FK 컬럼명)
    private final String referencedEntity;   // ex. "User"
    private final String fkRelationType;     // ex. "ManyToOne", "OneToMany", "OneToOne"

    public FkMapping(String entityClass, String fieldName, String tableName,
                     String joinColumnName, String referencedEntity, String fkRelationType) {
        this.entityClass       = entityClass;
        this.fieldName         = fieldName;
        this.tableName         = tableName;
        this.joinColumnName    = joinColumnName;
        this.referencedEntity  = referencedEntity;
        this.fkRelationType    = fkRelationType;
    }

    public String getEntityClass()      { return entityClass; }
    public String getFieldName()        { return fieldName; }
    public String getTableName()        { return tableName; }
    public String getJoinColumnName()   { return joinColumnName; }
    public String getReferencedEntity() { return referencedEntity; }
    public String getFkRelationType()   { return fkRelationType; }

    /** 그래프 노드 ID: FK_FIELD 타입  ex. "fkfield:Post.author" */
    public String getFkFieldNodeId() {
        return "fkfield:" + entityClass + "." + fieldName;
    }

    /** 그래프 노드 ID: COLUMN 타입  ex. "col:posts.author_id" */
    public String getColumnNodeId() {
        return "col:" + tableName + "." + joinColumnName;
    }

    @Override
    public String toString() {
        return String.format("%s.%s (@%s) → %s [col: %s.%s]",
                entityClass, fieldName, fkRelationType,
                referencedEntity, tableName, joinColumnName);
    }
}
