package schemaguard.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationHistoryTest {

    @TempDir
    Path dir;

    private File touch(String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "-- " + name);
        return p.toFile();
    }

    private static List<String> names(List<File> files) {
        return files.stream().map(File::getName).toList();
    }

    @Test
    void earlierFilesInVNumberOrder() throws IOException {
        touch("V1__init.sql");
        touch("V1_1__fix.sql");
        touch("V2.sql");                 // community 처럼 설명 없는 이름
        touch("V10__later.sql");         // 문자열 순서로는 V2 보다 앞이지만 버전은 더 크다
        touch("R__views.sql");           // 반복 실행 마이그레이션은 버전 순서가 없다
        touch("notes.txt");
        File current = touch("V3__current.sql");

        assertEquals(List.of("V1__init.sql", "V1_1__fix.sql", "V2.sql"), names(MigrationHistory.earlierThan(current)));
    }

    @Test
    void zeroAndDottedVersions() throws IOException {
        touch("V0.sql");
        touch("V1.2__a.sql");
        touch("V1.10__b.sql");
        File current = touch("V1.11__c.sql");
        assertEquals(List.of("V0.sql", "V1.2__a.sql", "V1.10__b.sql"), names(MigrationHistory.earlierThan(current)));
    }

    @Test
    void historyFolderCanDifferFromMigrationFolder() throws IOException {
        touch("V1__init.sql");
        touch("V5__later.sql");
        Path other = dir.resolve("pr");
        Files.createDirectories(other);
        Path current = other.resolve("V3__current.sql");
        Files.writeString(current, "-- current");
        assertEquals(List.of("V1__init.sql"), names(MigrationHistory.earlierThan(current.toFile(), dir.toFile())));
        assertTrue(MigrationHistory.hasVersion(current.toFile()));
        assertFalse(MigrationHistory.hasVersion(new File("R__views.sql")));
    }

    @Test
    void nonVersionedFileHasNoHistory() throws IOException {
        touch("V1__init.sql");
        File current = touch("drop_email.sql");
        assertTrue(MigrationHistory.earlierThan(current).isEmpty());
    }
}
