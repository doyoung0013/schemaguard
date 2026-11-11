package schemaguard.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import schemaguard.model.ControllerInfo;
import schemaguard.model.EntityMapping;
import schemaguard.model.FkMapping;
import schemaguard.model.RepositoryInfo;
import schemaguard.model.ServiceInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Java 소스 파일을 파일당 한 번만 파싱하고, 만들어진 AST를
 * Entity / Repository / Service / Controller 추출기 네 개가 공유한다.
 *
 * 이전에는 네 파서가 각자 StaticJavaParser.parse()를 호출해 파일 하나를 네 번 파싱했다.
 * AST는 파일 하나를 처리한 뒤 바로 참조를 놓으므로, 한 번에 메모리에 남는 AST는 최대 한 개다.
 */
public class SourceScanner {

    public record Result(List<EntityMapping> entities,
                         List<FkMapping> fkMappings,
                         List<RepositoryInfo> repositories,
                         List<ServiceInfo> services,
                         List<ControllerInfo> controllers,
                         List<String> failedFiles,
                         int parseCalls) {}

    private final EntityParser entityParser = new EntityParser();
    private final RepositoryParser repositoryParser = new RepositoryParser();
    private final ServiceParser serviceParser = new ServiceParser();
    private final ControllerParser controllerParser = new ControllerParser();

    public Result scan(List<File> javaFiles) {
        List<EntityMapping> entities = new ArrayList<>();
        List<FkMapping> fkMappings = new ArrayList<>();
        List<RepositoryInfo> repos = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<ControllerInfo> controllers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int parseCalls = 0;

        for (File javaFile : javaFiles) {
            try {
                parseCalls++;
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                String path = javaFile.getAbsolutePath();

                EntityParser.ParseResult pr = entityParser.parse(cu, path);
                entities.addAll(pr.entityMappings());
                fkMappings.addAll(pr.fkMappings());

                repos.addAll(repositoryParser.parse(cu, path));
                services.addAll(serviceParser.parse(cu, path));
                controllers.addAll(controllerParser.parse(cu, path));
            } catch (Exception e) {
                System.err.println("  [WARN] Parse failed: " + javaFile.getName()
                        + " (" + e.getMessage() + ")");
                failed.add(javaFile.getPath());
            }
        }

        return new Result(entities, fkMappings, repos, services, controllers, failed, parseCalls);
    }

    /**
     * dir 아래의 .java 파일을 재귀적으로 모은다. 디렉터리가 없으면 빈 목록.
     *
     * listFiles()의 순서는 OS/파일시스템마다 다르다(NTFS는 대체로 이름순, ext4는 해시 순).
     * 파일 순서가 그래프 구성 순서와 영향 경로 선택에 번지지 않도록 경로 기준으로 정렬한다.
     */
    public static List<File> collectJavaFiles(File dir) {
        List<File> result = new ArrayList<>();
        collect(dir, result);
        result.sort(Comparator.comparing(f -> f.getPath().replace('\\', '/')));
        return result;
    }

    private static void collect(File dir, List<File> result) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, result);
            } else if (f.getName().endsWith(".java")) {
                result.add(f);
            }
        }
    }
}
