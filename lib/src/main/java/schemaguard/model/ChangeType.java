package schemaguard.model;

public enum ChangeType {
    DROP_COLUMN, RENAME_COLUMN, MODIFY_TYPE,
    DROP_TABLE, RENAME_TABLE, ADD_NOT_NULL, ADD_UNIQUE, ADD_COLUMN
}
