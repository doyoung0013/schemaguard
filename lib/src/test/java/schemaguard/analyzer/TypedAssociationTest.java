package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * getter 체인의 루트 타입을 해석해 연관을 정확히 따라가는지 (이전에는 속성 이름만으로 추정).
 *
 * 핵심 사례: 같은 이름의 연관이 서로 다른 엔티티를 가리킨다.
 *   Pet.owner  → User     (users)
 *   Shop.owner → Company  (companies)
 * 이름 추정은 "owner" 가 두 엔티티를 가리켜 포기했다(놓침). 루트 타입을 알면 정확히 하나로 정해진다.
 */
class TypedAssociationTest {

    private static final String USER = """
            @Entity @Table(name = "users")
            public class User { @Id private Long id; @Column(name = "name") private String name; }
            """;
    private static final String COMPANY = """
            @Entity @Table(name = "companies")
            public class Company { @Id private Long id; @Column(name = "name") private String name; }
            """;
    private static final String PET = """
            @Entity @Table(name = "pets")
            public class Pet { @Id private Long id;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id") private User owner; }
            """;
    private static final String SHOP = """
            @Entity @Table(name = "shops")
            public class Shop { @Id private Long id;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id") private Company owner; }
            """;
    private static final String VISIT = """
            @Entity @Table(name = "visits")
            public class Visit { @Id private Long id;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "pet_id") private Pet pet; }
            """;
    private static final String REPOS = """
            public interface PetRepository extends JpaRepository<Pet, Long> { List<Pet> findByIdGreaterThan(Long id); }
            public interface ShopRepository extends JpaRepository<Shop, Long> { }
            public interface VisitRepository extends JpaRepository<Visit, Long> { }
            """;
    private static final String SERVICES = """
            @Service
            public class PetService {
                private PetRepository petRepository;
                private VisitRepository visitRepository;
                private ShopRepository shopRepository;

                // 1) 선언 타입: 파라미터
                public String ownerOf(Pet pet) { return pet.getOwner().getName(); }
                // 2) 선언 타입: 지역 변수에 연관을 꺼낸 뒤 접근 (이름 추정은 체인이 끊겨 놓쳤다)
                public String viaLocal(Long id) {
                    Visit v = visitRepository.findById(id).orElseThrow();
                    Pet p = v.getPet();
                    return p.getOwner().getName();
                }
                // 3) 추론: 컬렉션 원소 타입 (타입을 생략한 람다)
                public Object fromList() {
                    List<Pet> pets = petRepository.findByIdGreaterThan(0L);
                    return pets.stream().filter(x -> x.getId() > 0).map(x -> x.getOwner().getName()).toList();
                }
                // 4) 추론: 리포지토리 호출 결과에서 시작하는 스트림
                public Object fromRepoStream() {
                    return shopRepository.findAll().stream().map(s -> s.getOwner().getName()).toList();
                }
                // 5) 추론: 리포지토리 호출 + orElseThrow 가 체인 루트
                public String fromRepoCall(Long id) {
                    return visitRepository.findById(id).orElseThrow().getPet().getOwner().getName();
                }
                // 6) 루트가 엔티티가 아닌 DTO → 연관이 아니다 (이름 추정은 Pet.owner/Shop.owner 중 하나로 오인할 수 있었다)
                public String fromView(PetView view) { return view.getOwner().getName(); }
            }
            """;
    private static final String CONTROLLER = """
            @RestController
            public class PetController {
                private PetService petService;
                @GetMapping("/owner")      public Object a() { return petService.ownerOf(null); }
                @GetMapping("/local")      public Object b() { return petService.viaLocal(1L); }
                @GetMapping("/list")       public Object c() { return petService.fromList(); }
                @GetMapping("/repostream") public Object d() { return petService.fromRepoStream(); }
                @GetMapping("/repocall")   public Object e() { return petService.fromRepoCall(1L); }
                @GetMapping("/view")       public Object f() { return petService.fromView(null); }
            }
            """;

    private static DependencyGraphBuilder build(String... sources) throws Exception {
        List<EntityMapping> entities = new ArrayList<>();
        List<FkMapping> fks = new ArrayList<>();
        List<RepositoryInfo> repos = new ArrayList<>();
        List<ServiceInfo> services = new ArrayList<>();
        List<ControllerInfo> controllers = new ArrayList<>();
        for (String src : sources) {
            EntityParser.ParseResult pr = new EntityParser().parseSource(src);
            entities.addAll(pr.entityMappings());
            fks.addAll(pr.fkMappings());
            repos.addAll(new RepositoryParser().parseSource(src));
            services.addAll(new ServiceParser().parseSource(src));
            controllers.addAll(new ControllerParser().parseSource(src));
        }
        DependencyGraphBuilder b = new DependencyGraphBuilder();
        b.build(entities, fks, repos, services, controllers);
        return b;
    }

    private static Set<String> apis(DependencyGraphBuilder b, String sql) {
        Set<String> ids = new TreeSet<>();
        ImpactAnalyzer.of(b).analyze(new SqlMigrationParser().parseSQL(sql).changes())
                .getAffectedApis().forEach(a -> ids.add(a.getApiId()));
        return ids;
    }

    private static DependencyGraphBuilder project() throws Exception {
        return build(USER, COMPANY, PET, SHOP, VISIT, REPOS, SERVICES, CONTROLLER);
    }

    @Test
    void sameAttributeNameIsResolvedByRootType() throws Exception {
        DependencyGraphBuilder b = project();
        // users.name 은 Pet.owner(User)를 통해서만: 파라미터, 지역 변수, 컬렉션 원소, 리포지토리 호출 루트
        assertEquals(Set.of("GET /owner", "GET /local", "GET /list", "GET /repocall"),
                apis(b, "ALTER TABLE users DROP COLUMN name;"));
        // companies.name 은 Shop.owner(Company)를 통해서만
        assertEquals(Set.of("GET /repostream"), apis(b, "ALTER TABLE companies DROP COLUMN name;"));
    }

    @Test
    void nonEntityRootIsNotTreatedAsAssociation() throws Exception {
        DependencyGraphBuilder b = project();
        assertFalse(apis(b, "ALTER TABLE users DROP COLUMN name;").contains("GET /view"));
        assertFalse(apis(b, "ALTER TABLE companies DROP COLUMN name;").contains("GET /view"));
        assertEquals(1, b.getAssociationResolution().getOrDefault("root-not-entity", 0));
    }

    @Test
    void parserRecordsRootTypeAndHowItWasFound() throws Exception {
        ServiceInfo svc = new ServiceParser().parseSource(SERVICES).get(0);
        Map<String, String> byMethod = new TreeMap<>();
        for (ServiceInfo.AssociationAccess a : svc.getAssociationAccesses())
            byMethod.merge(a.callerMethod(), a.typeSource() + ":" + a.rootType() + ":" + a.path(), (x, y) -> x + " " + y);
        assertEquals("DECLARED:Pet:[owner]", byMethod.get("ownerOf"));
        assertEquals("DECLARED:Pet:[owner]", byMethod.get("viaLocal"));
        assertEquals("INFERRED:Pet:[owner]", byMethod.get("fromList"));
        assertEquals("INFERRED:repo:ShopRepository:[owner]", byMethod.get("fromRepoStream"));
        assertEquals("INFERRED:repo:VisitRepository:[pet] INFERRED:repo:VisitRepository:[pet, owner]",
                byMethod.get("fromRepoCall"));
        assertEquals("DECLARED:PetView:[owner]", byMethod.get("fromView"));
    }

    @Test
    void localVariableOfEntityTypeIsRecordedAsEntityLoad() throws Exception {
        ServiceInfo svc = new ServiceParser().parseSource(SERVICES).get(0);
        List<String> viaLocal = svc.getEntityTypeAccesses().stream()
                .filter(a -> a.callerMethod().equals("viaLocal")).map(ServiceInfo.EntityTypeAccess::entityType).toList();
        assertTrue(viaLocal.contains("Pet"), viaLocal.toString());     // p.getOwner() 로 Pet 초기화
    }

    // ── symbol solver (옵션): 선언·추론 규칙으로 모르는 루트 ────────────────────

    @TempDir
    Path tmp;

    @Test
    void symbolSolverResolvesWhatRulesCannot() throws Exception {
        // 같은 클래스의 메서드가 돌려주는 List<Pet> 의 원소: 규칙(지역 변수·리포지토리 호출)으로는 알 수 없다.
        Path src = tmp.resolve("src/main/java");
        // symbol solver 는 람다 타입을 추론할 때 본문의 getter 반환 타입까지 풀어야 한다.
        // Lombok @Getter 처럼 getter 가 소스에 없으면 실패한다 (community 에서 solver 가 0건이었던 이유) → 여기선 직접 선언.
        write(src, "demo/User.java", "package demo;\n" + USER.replace(
                "private String name; }", "private String name; public String getName() { return name; } }"));
        write(src, "demo/Pet.java", "package demo;\n" + PET.replace(
                "private User owner; }", "private User owner; public User getOwner() { return owner; } }"));
        // Shop.owner(Company) 도 있어 "owner" 이름 추정은 실패한다 → solver 가 없으면 놓친다.
        write(src, "demo/Company.java", "package demo;\n" + COMPANY);
        write(src, "demo/Shop.java", "package demo;\n" + SHOP);
        write(src, "demo/PetRepository.java", "package demo;\npublic interface PetRepository extends JpaRepository<Pet, Long> { }\n");
        write(src, "demo/HelperService.java", """
                package demo;
                import java.util.List;
                @Service
                public class HelperService {
                    private List<Pet> loadPets() { return List.of(); }
                    public Object names() { return loadPets().stream().map(p -> p.getOwner().getName()).toList(); }
                }
                """);
        write(src, "demo/HelperController.java", """
                package demo;
                @RestController
                public class HelperController {
                    private HelperService helperService;
                    @GetMapping("/names") public Object names() { return helperService.names(); }
                }
                """);
        List<File> files = JavaSourceScanner.collectJavaFiles(src.toFile());

        JavaSourceScanner.Result withoutSolver = new JavaSourceScanner().scan(files, null);
        JavaSourceScanner withScanner = new JavaSourceScanner();
        JavaSourceScanner.Result withSolver = withScanner.scan(files, src.toFile());

        assertEquals(Set.of(), apis(graph(withoutSolver), "ALTER TABLE users DROP COLUMN name;"));
        assertEquals(Set.of("GET /names"), apis(graph(withSolver), "ALTER TABLE users DROP COLUMN name;"));
        assertTrue(withScanner.typeResolutionCounts()[2] >= 1, Arrays.toString(withScanner.typeResolutionCounts()));
    }

    private static DependencyGraphBuilder graph(JavaSourceScanner.Result r) {
        DependencyGraphBuilder b = new DependencyGraphBuilder();
        b.build(r.entities(), r.fkMappings(), r.repositories(), r.services(), r.controllers());
        return b;
    }

    private static void write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
