package schemaguard.model;

public enum RelationType {
    CALLS,    // 메서드 호출 관계  (Controller→Service, Service→Repository)
    USES,     // 필드 사용 관계   (Repository→Field)
    MAPS,      // 매핑 관계        (Field→Column, API→Controller)
    FK_MAPS,  // FK 컬럼 매핑       (FkField→Column)  @JoinColumn 관계
    LOADS     // 연관 엔티티 로딩    (Repository/Service→EntityLoad, EntityLoad→EntityLoad/Column)
}
 
