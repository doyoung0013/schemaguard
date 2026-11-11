package schemaguard.parser;

import org.junit.jupiter.api.Test;
import schemaguard.model.ChangeType;
import schemaguard.model.SchemaChange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationParserTest {

    private final MigrationParser parser = new MigrationParser();

    private static long count(List<SchemaChange> changes, ChangeType type) {
        return changes.stream().filter(c -> c.getChangeType() == type).count();
    }

    @Test
    void oneBadStatementDoesNotHideTheOthers() {
        StringBuilder sql = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            sql.append("ALTER TABLE users DROP COLUMN col").append(i).append(";\n");
        }
        sql.append("THIS IS NOT VALID SQL;\n");   // 10번째 줄

        MigrationParser.ParseResult r = parser.parseSQL(sql.toString());

        assertEquals(10, r.totalStatements());
        assertEquals(9, count(r.changes(), ChangeType.DROP_COLUMN));
        assertEquals(1, r.failures().size());
        assertEquals(10, r.failures().get(0).line());
        assertTrue(r.hasFailures());
    }

    @Test
    void backtickedDropForeignKeyNoLongerSwallowsDropColumn() {
        String sql = """
                ALTER TABLE users DROP COLUMN email;
                ALTER TABLE `posts` DROP FOREIGN KEY `fk_post_author`;
                ALTER TABLE users DROP COLUMN nickname;
                """;

        MigrationParser.ParseResult r = parser.parseSQL(sql);

        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        assertEquals(2, count(r.changes(), ChangeType.DROP_COLUMN));
        SchemaChange fk = r.changes().stream()
                .filter(c -> c.getChangeType() == ChangeType.DROP_FOREIGN_KEY)
                .findFirst().orElseThrow();
        assertEquals("posts", fk.getTableName());
        assertEquals("fk_post_author", fk.getFkName());
    }

    @Test
    void quotedIdentifiersAreNormalizedForGraphMatching() {
        MigrationParser.ParseResult r =
                parser.parseSQL("ALTER TABLE `mydb`.`users` DROP COLUMN `email`;");

        assertFalse(r.hasFailures());
        SchemaChange c = r.changes().get(0);
        assertEquals("users", c.getTableName());
        assertEquals("email", c.getColumnName());
    }

    @Test
    void addForeignKeyWithOnDeleteClauseParses() {
        MigrationParser.ParseResult r = parser.parseSQL(
                "ALTER TABLE posts ADD CONSTRAINT fk_author FOREIGN KEY (author_id) "
                        + "REFERENCES users(id) ON DELETE CASCADE ON UPDATE NO ACTION;");

        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        SchemaChange c = r.changes().get(0);
        assertEquals(ChangeType.ADD_FOREIGN_KEY, c.getChangeType());
        assertEquals("author_id", c.getColumnName());
        assertEquals("users", c.getReferencedTable());
    }

    @Test
    void dropAndAddForeignKeyOnSameTableBecomesModify() {
        MigrationParser.ParseResult r = parser.parseSQL("""
                ALTER TABLE posts DROP FOREIGN KEY fk_author;
                ALTER TABLE posts ADD CONSTRAINT fk_author FOREIGN KEY (author_id) REFERENCES members(id);
                """);

        assertEquals(1, r.changes().size());
        assertEquals(ChangeType.MODIFY_FK_REFERENCE, r.changes().get(0).getChangeType());
        assertEquals("members", r.changes().get(0).getReferencedTable());
    }

    @Test
    void semicolonsInsideStringsAndCommentsDoNotSplitStatements() {
        String sql = """
                -- remove email; it is no longer used
                /* multi-line
                   comment; still a comment */
                UPDATE users SET bio = 'a;b' WHERE id = 1;
                ALTER TABLE users DROP COLUMN email;
                """;

        MigrationParser.ParseResult r = parser.parseSQL(sql);

        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        assertEquals(2, r.totalStatements());
        assertEquals(1, count(r.changes(), ChangeType.DROP_COLUMN));
    }

    @Test
    void alterClauseUnknownToJsqlParserIsReportedAsFailure() {
        // JSQLParser는 이 구문을 예외 없이 Alter(UNSPECIFIC)로 돌려준다.
        MigrationParser.ParseResult r = parser.parseSQL("""
                ALTER TABLE users DROP COLUMN email;
                ALTER TABLE users FROBNICATE email;
                """);

        assertEquals(1, count(r.changes(), ChangeType.DROP_COLUMN));
        assertEquals(1, r.failures().size());
        assertEquals(2, r.failures().get(0).line());
    }

    @Test
    void renameTableIsDetectedInBothSyntaxes() {
        MigrationParser.ParseResult r = parser.parseSQL("""
                ALTER TABLE `users` RENAME TO `members`;
                RENAME TABLE posts TO articles, comments TO replies;
                """);

        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        assertEquals(3, count(r.changes(), ChangeType.RENAME_TABLE));
        SchemaChange first = r.changes().get(0);
        assertEquals("users", first.getTableName());
        assertEquals("members", first.getNewName());
        assertTrue(r.changes().stream().anyMatch(c ->
                c.getTableName().equals("comments") && c.getNewName().equals("replies")));
    }

    @Test
    void alterColumnSetNotNullAndTypeChangeAreDetected() {
        MigrationParser.ParseResult r = parser.parseSQL("""
                ALTER TABLE users ALTER COLUMN email SET NOT NULL;
                ALTER TABLE users ALTER COLUMN age TYPE BIGINT;
                ALTER TABLE users ALTER COLUMN bio DROP NOT NULL;
                ALTER TABLE users ALTER COLUMN bio SET DEFAULT 'x';
                """);

        assertFalse(r.hasFailures(), () -> "unexpected failures: " + r.failures());
        assertEquals(2, r.changes().size());   // DROP NOT NULL / SET DEFAULT는 위험 없음 → 무시
        SchemaChange notNull = r.changes().get(0);
        assertEquals(ChangeType.ADD_NOT_NULL, notNull.getChangeType());
        assertEquals("email", notNull.getColumnName());
        SchemaChange type = r.changes().get(1);
        assertEquals(ChangeType.MODIFY_TYPE, type.getChangeType());
        assertEquals("age", type.getColumnName());
    }

    @Test
    void emptyOrCommentOnlyFileHasNoStatementsAndNoFailures() {
        MigrationParser.ParseResult r = parser.parseSQL("-- nothing here\n\n");

        assertEquals(0, r.totalStatements());
        assertTrue(r.changes().isEmpty());
        assertFalse(r.hasFailures());
    }
}
