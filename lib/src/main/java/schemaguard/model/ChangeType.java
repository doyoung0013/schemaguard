package schemaguard.model;

public enum ChangeType {
    // 컬럼 변경
    DROP_COLUMN,
    RENAME_COLUMN,
    MODIFY_TYPE,
    ADD_NOT_NULL,
    ADD_UNIQUE,
    ADD_COLUMN,
    NARROW_COLUMN,        // 길이/범위를 줄이는 타입 변경 (기존·신규 값이 잘리거나 저장 실패 가능)
    MODIFY_COMPATIBLE,    // 엔티티 매핑과 호환되는 타입 변경 (코드 영향 없음)
 
    // 테이블 변경
    DROP_TABLE,
    RENAME_TABLE,
 
    // FK 변경 (신규)
    DROP_FOREIGN_KEY,
    DROP_FK_COLUMN,       
    MODIFY_FK_REFERENCE,  
    ADD_FOREIGN_KEY        
}
