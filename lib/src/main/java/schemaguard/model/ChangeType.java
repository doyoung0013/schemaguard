package schemaguard.model;

public enum ChangeType {
    // 컬럼 변경
    DROP_COLUMN,
    RENAME_COLUMN,
    MODIFY_TYPE,
    ADD_NOT_NULL,
    ADD_UNIQUE,
    ADD_COLUMN,
 
    // 테이블 변경
    DROP_TABLE,
    RENAME_TABLE,
 
    // FK 변경 (신규)
    DROP_FOREIGN_KEY,
    DROP_FK_COLUMN,       
    MODIFY_FK_REFERENCE,  
    ADD_FOREIGN_KEY        
}
