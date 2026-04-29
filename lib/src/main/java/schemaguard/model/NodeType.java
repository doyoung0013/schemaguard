package schemaguard.model;

public enum NodeType {
    API,          // HTTP 엔드포인트  ex. GET /users/{id}
    CONTROLLER,   // Controller 메서드
    SERVICE,      // Service 메서드
    REPOSITORY,   // Repository 메서드
    FIELD,        // Entity 필드
    COLUMN        // DB 컬럼
}
