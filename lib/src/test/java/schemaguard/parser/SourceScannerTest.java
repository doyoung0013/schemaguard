package schemaguard.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import schemaguard.bench.SampleProjectGenerator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceScannerTest {

    @TempDir
    Path tmp;

    @Test
    void parsesEachFileOnceAndMatchesPerParserResults() throws Exception {
        SampleProjectGenerator.generate(tmp, 40);
        List<File> files = SourceScanner.collectJavaFiles(tmp.resolve("src/main/java").toFile());
        assertEquals(40, files.size());

        SourceScanner.Result shared = new SourceScanner().scan(files);
        assertEquals(40, shared.parseCalls());
        assertTrue(shared.failedFiles().isEmpty());

        // 기존 방식(파서마다 파일을 따로 파싱)과 추출 결과가 같아야 한다.
        int entities = 0, fks = 0, repos = 0, services = 0, controllers = 0;
        for (File f : files) {
            EntityParser.ParseResult pr = new EntityParser().parse(f);
            entities += pr.entityMappings().size();
            fks += pr.fkMappings().size();
            repos += new RepositoryParser().parse(f).size();
            services += new ServiceParser().parse(f).size();
            controllers += new ControllerParser().parse(f).size();
        }
        assertEquals(entities, shared.entities().size());
        assertEquals(fks, shared.fkMappings().size());
        assertEquals(repos, shared.repositories().size());
        assertEquals(services, shared.services().size());
        assertEquals(controllers, shared.controllers().size());
        assertEquals(10, shared.controllers().size());
        assertEquals(9, shared.fkMappings().size());   // 첫 그룹을 뺀 나머지가 parent FK를 가진다
    }

    @Test
    void brokenFileIsReportedAndOthersStillScanned() throws Exception {
        SampleProjectGenerator.generate(tmp, 8);
        Path broken = tmp.resolve("src/main/java/com/example/Broken.java");
        Files.writeString(broken, "public class Broken { void x( { }");

        List<File> files = SourceScanner.collectJavaFiles(tmp.resolve("src/main/java").toFile());
        SourceScanner.Result r = new SourceScanner().scan(files);

        assertEquals(9, r.parseCalls());
        assertEquals(1, r.failedFiles().size());
        assertTrue(r.failedFiles().get(0).endsWith("Broken.java"));
        assertEquals(2, r.controllers().size());
    }

    @Test
    void missingDirectoryYieldsNoFiles() {
        assertTrue(SourceScanner.collectJavaFiles(tmp.resolve("nope").toFile()).isEmpty());
    }
}
