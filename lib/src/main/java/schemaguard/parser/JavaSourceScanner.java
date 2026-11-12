package schemaguard.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 마지막 scan 의 getter 체인 루트 타입 해석 건수. 스레드별 ServiceParser 의 통계를 합친 값이다. */
    private int[] typeResolutionCounts = new int[4];

    public Result scan(List<File> javaFiles) {
        return scan(javaFiles, null, 1);
    }

    public Result scan(List<File> javaFiles, File sourceRoot) {
        return scan(javaFiles, sourceRoot, 1);
    }

    /**
     * @param sourceRoot 패키지 디렉터리의 루트(src/main/java). 주어지면 symbol solver 를 붙여,
     *                   선언으로 타입을 알 수 없는 getter 체인 루트(타입 생략 람다 파라미터 등)의 타입을 해석한다.
     *                   타입 해석은 체인 루트에만 필요할 때 시도하고, 실패하면 이름 추정으로 넘어간다.
     *                   null 이면 symbol solver 없이 파싱한다.
     * @param threads    파싱 스레드 수. 1 이면 호출한 스레드에서 차례로 파싱한다.
     *
     * 병렬로 파싱해도 결과는 순차 파싱과 같다.
     *  - JavaParser 인스턴스(symbol solver 와 그 캐시 포함)와 네 추출기를 스레드마다 따로 둔다.
     *    ServiceParser 의 타입 해석 통계처럼 상태가 있는 객체를 스레드끼리 공유하지 않는다.
     *  - 파일별 결과를 입력 순서의 칸에 담아 두었다가 입력 순서대로 합친다.
     *    그래서 그래프 구성 순서와 파싱 실패 목록·경고 출력 순서가 순차 파싱과 같다.
     */
    public Result scan(List<File> javaFiles, File sourceRoot, int threads) {
        int n = javaFiles.size();
        FileResult[] results = new FileResult[n];
        int workers = Math.max(1, Math.min(threads, n));
        List<Worker> pool = new ArrayList<>();

        if (workers == 1) {
            Worker w = new Worker(newParser(sourceRoot));
            pool.add(w);
            for (int i = 0; i < n; i++) results[i] = w.scan(javaFiles.get(i));
        } else {
            AtomicInteger next = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(workers, r -> {
                Thread t = new Thread(r, "schemaguard-parse");
                t.setDaemon(true);
                return t;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int k = 0; k < workers; k++) {
                    Worker w = new Worker(newParser(sourceRoot));
                    pool.add(w);
                    futures.add(executor.submit(() -> {
                        // 파일 크기가 제각각이라 미리 나누지 않고, 끝난 스레드가 다음 파일을 가져간다.
                        for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement())
                            results[i] = w.scan(javaFiles.get(i));
                    }));
                }
                for (Future<?> f : futures) f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Java source scan interrupted", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Java source scan failed", e.getCause());
            } finally {
                executor.shutdownNow();
            }
        }

        List<EntityMapping> entities = new ArrayList<>();
        List<FkMapping> fkMappings = new ArrayList<>();
        List<IndexDef> entityIndexes = new ArrayList<>();
        List<RepositoryInfo> repos = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<ControllerInfo> controllers = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            FileResult r = results[i];
            if (r.error() != null) {
                System.err.println("  [WARN] Parse failed: " + javaFiles.get(i).getName() + " (" + r.error() + ")");
                failed.add(javaFiles.get(i).getPath());
                continue;
            }
            entities.addAll(r.entities());
            fkMappings.addAll(r.fkMappings());
            entityIndexes.addAll(r.indexes());
            repos.addAll(r.repositories());
            services.addAll(r.services());
            controllers.addAll(r.controllers());
        }

        int[] counts = new int[4];
        for (Worker w : pool) {
            int[] c = w.serviceParser.typeResolutionCounts();
            for (int k = 0; k < counts.length; k++) counts[k] += c[k];
        }
        typeResolutionCounts = counts;

        return new Result(entities, fkMappings, repos, services, controllers, failed, n, entityIndexes);
    }

    /** getter 체인 루트 타입 해석 건수 {선언, 추론, symbol solver, 해석 실패} */
    public int[] typeResolutionCounts() { return typeResolutionCounts.clone(); }

    /**
     * 자동 스레드 수: 사용 가능한 코어 수의 절반(1~4).
     * 파싱 중에는 JIT 컴파일러와 GC 스레드도 코어를 쓴다. 4코어(GitHub 호스팅 러너와 같은 크기)에서
     * 파싱 스레드 4개는 1,000~5,000 파일에서 2개보다 느리거나 비슷했다(CHANGELOG 16번).
     */
    public static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** 파일 하나의 추출 결과. 파싱에 실패하면 error 만 채운다. */
    private record FileResult(List<EntityMapping> entities, List<FkMapping> fkMappings, List<IndexDef> indexes,
                              List<RepositoryInfo> repositories, List<ServiceInfo> services,
                              List<ControllerInfo> controllers, String error) {
        static FileResult failed(String error) {
            return new FileResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), error);
        }
    }

    /** 스레드 하나가 쓰는 파서와 추출기 묶음. 스레드끼리 공유하지 않는다. */
    private static final class Worker {
        final JavaParser parser;
        final EntityParser entityParser = new EntityParser();
        final RepositoryParser repositoryParser = new RepositoryParser();
        final ServiceParser serviceParser = new ServiceParser();
        final ControllerParser controllerParser = new ControllerParser();

        Worker(JavaParser parser) { this.parser = parser; }

        FileResult scan(File javaFile) {
            try {
                // JavaParser 인스턴스는 문법 오류에도 예외 없이 부분 결과를 돌려준다. 실패는 실패로 보고한다.
                com.github.javaparser.ParseResult<CompilationUnit> parsed = parser.parse(javaFile);
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty())
                    throw new com.github.javaparser.ParseProblemException(parsed.getProblems());
                CompilationUnit cu = parsed.getResult().get();
                String path = javaFile.getAbsolutePath();

                EntityParser.ParseResult pr = entityParser.parse(cu, path);
                return new FileResult(pr.entityMappings(), pr.fkMappings(), pr.indexes(),
                        repositoryParser.parse(cu, path), serviceParser.parse(cu, path),
                        controllerParser.parse(cu, path), null);
            } catch (Exception e) {
                return FileResult.failed(String.valueOf(e.getMessage()));
            }
        }
    }

    private static JavaParser newParser(File sourceRoot) {
        // JavaParser 기본값(POPULAR = Java 11 문법)은 텍스트 블록(""")과 record 를 읽지 못해 그 파일을 통째로 파싱 실패로 뺀다.
        // Spring Boot 3 은 Java 17 이상이므로 Java 17 문법으로 읽는다 (CHANGELOG 18, 2단계에서 발견).
        ParserConfiguration.LanguageLevel level = ParserConfiguration.LanguageLevel.JAVA_17;
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(level);
        File root = packageRoot(sourceRoot);
        if (root != null) {
            ParserConfiguration plain = new ParserConfiguration().setLanguageLevel(level);
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
