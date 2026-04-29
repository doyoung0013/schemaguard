package schemaguard.model;

public enum RelationType {
    CALLS,    // 메서드 호출 관계  (Controller→Service, Service→Repository)
    USES,     // 필드 사용 관계   (Repository→Field)
    MAPS      // 매핑 관계        (Field→Column, API→Controller)
}
 
