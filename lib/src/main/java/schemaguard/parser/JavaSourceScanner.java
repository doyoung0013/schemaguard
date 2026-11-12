package schemaguard.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import schemaguard.model.ControllerInfo;
import schemaguard.model.EntityMapping;
import schemaguard.model.FkMapping;
import schemaguard.model.IndexDef;
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
public class JavaSourceScanner {

    public record Result(List<EntityMapping> entities,
                         List<FkMapping> fkMappings,
                         List<RepositoryInfo> repositories,
                         List<ServiceInfo> services,
                         List<ControllerInfo> controllers,
                         List<String> failedFiles,
                         int parseCalls,
                         List<IndexDef> entityIndexes) {   // @Table 의 uniqueConstraints / indexes
        public Result(List<EntityMapping> entities, List<FkMapping> fkMappings, List<RepositoryInfo> repositories,
                      List<ServiceInfo> services, List<ControllerInfo> controllers, List<String> failedFiles,
                      int parseCalls) {
            this(entities, fkMappings, repositories, services, controllers, failedFiles, parseCalls, List.of());
        }
    }

    private final EntityParser entityParser = new EntityParser();
    private final RepositoryParser repositoryParser = new RepositoryParser();
    private final ServiceParser serviceParser = new ServiceParser();
    private final ControllerParser controllerParser = new ControllerParser();

    public Result scan(List<File> javaFiles) {
        return scan(javaFiles, null);
    }

    /**
     * @param sourceRoot 패키지 디렉터리의 루트(src/main/java). 주어지면 symbol solver 를 붙여,
     *                   선언으로 타입을 알 수 없는 getter 체인 루트(타입 생략 람다 파라미터 등)의 타입을 해석한다.
     *                   타입 해석은 체인 루트에만 필요할 때 시도하고, 실패하면 이름 추정으로 넘어간다.
     *                   null 이면 symbol solver 없이 파싱한다.
     */
    public Result scan(List<File> javaFiles, File sourceRoot) {
        JavaParser parser = newParser(sourceRoot);
        List<EntityMapping> entities = new ArrayList<>();
        List<FkMapping> fkMappings = new ArrayList<>();
        List<IndexDef> entityIndexes = new ArrayList<>();
        List<RepositoryInfo> repos = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<ControllerInfo> controllers = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int parseCalls = 0;

        for (File javaFile : javaFiles) {
            try {
                parseCalls++;
                // JavaParser 인스턴스는 문법 오류에도 예외 없이 부분 결과를 돌려준다. 실패는 실패로 보고한다.
                com.github.javaparser.ParseResult<CompilationUnit> parsed = parser.parse(javaFile);
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty())
                    throw new com.github.javaparser.ParseProblemException(parsed.getProblems());
                CompilationUnit cu = parsed.getResult().get();
                String path = javaFile.getAbsolutePath();

                EntityParser.ParseResult pr = entityParser.parse(cu, path);
                entities.addAll(pr.entityMappings());
                fkMappings.addAll(pr.fkMappings());
                entityIndexes.addAll(pr.indexes());

                repos.addAll(repositoryParser.parse(cu, path));
                services.addAll(serviceParser.parse(cu, path));
                controllers.addAll(controllerParser.parse(cu, path));
            } catch (Exception e) {
                System.err.println("  [WARN] Parse failed: " + javaFile.getName()
                        + " (" + e.getMessage() + ")");
                failed.add(javaFile.getPath());
            }
        }

        return new Result(entities, fkMappings, repos, services, controllers, failed, parseCalls, entityIndexes);
    }

    /** getter 체인 루트 타입 해석 건수 {선언, 추론, symbol solver, 해석 실패} */
    public int[] typeResolutionCounts() { return serviceParser.typeResolutionCounts(); }

    private static JavaParser newParser(File sourceRoot) {
        ParserConfiguration base = StaticJavaParser.getParserConfiguration();
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(base.getLanguageLevel());
        File root = packageRoot(sourceRoot);
        if (root != null) {
            ParserConfiguration plain = new ParserConfiguration().setLanguageLevel(base.getLanguageLevel());
            CombinedTypeSolver types = new CombinedTypeSolver(
                    new ReflectionTypeSolver(),                     // JDK (List, Optional, Stream ...)
                    new JavaParserTypeSolver(root, plain));          // 프로젝트 소스. Spring 등 외부 라이브러리는 없다.
            cfg.setSymbolResolver(new JavaSymbolSolver(types));
        }
        return new JavaParser(cfg);
    }

    /** --source 로 프로젝트 루트를 줬으면 src/main/java 를 쓴다. */
    private static File packageRoot(File sourceRoot) {
        if (sourceRoot == null || !sourceRoot.isDirectory()) return null;
        File nested = new File(sourceRoot, "src/main/java");
        return nested.isDirectory() ? nested : sourceRoot;
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
