package schemaguard.model;

public enum NodeType {
    API,          // HTTP 엔드포인트
    CONTROLLER,   // Controller 메서드
    SERVICE,      // Service 메서드
    REPOSITORY,   // Repository 메서드
    FIELD,        // 일반 Entity 필드
    FK_FIELD,     // FK 역할 Entity 필드   ex. @ManyToOne Post.author, @JoinColumn author_id
    COLUMN        // DB 컬럼
}
