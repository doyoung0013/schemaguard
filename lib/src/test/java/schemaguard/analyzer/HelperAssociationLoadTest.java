package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DTO·매퍼의 static 메서드에서 일어나는 연관 초기화.
 * 서비스가 @EntityGraph 없이 엔티티를 가져와 ReportResponse::from 으로 변환하면,
 * 지연 로딩 프록시는 DTO 안의 report.getPost().getTitle() 에서 초기화된다.
 */
class HelperAssociationLoadTest {

    private static final String USER = """
            @Entity @Table(name = "users")
            public class User { @Id private Long id; @Column(name = "email") private String email;
                                @Column(name = "nickname") private String nickname; }
            """;
    private static final String POST = """
            @Entity @Table(name = "posts")
            public class Post {
                @Id private Long id;
                @Column(name = "title") private String title;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id") private User author;
            }
            """;
    private static final String REPORT = """
            @Entity @Table(name = "reports")
            public class Report {
                @Id private Long id;
                @Column(name = "reason") private String reason;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "post_id") private Post post;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reporter_id") private User reporter;
            }
            """;
    private static final String REPORT_REPO = """
            public interface ReportRepository extends JpaRepository<Report, Long> {
                List<Report> findTop20ByOrderByIdDesc();
            }
            """;
    /** 연관을 초기화하는 DTO (메서드 참조로 호출) */
    private static final String SUMMARY_DTO = """
            public class ReportSummaryResponse {
                private String title; private String nickname;
                public static ReportSummaryResponse from(Report report) {
                    ReportSummaryResponse r = new ReportSummaryResponse();
                    r.title = report.getPost().getTitle();
                    r.nickname = AuthorDto.from(report.getReporter()).name;
                    return r;
                }
            }
            """;
    /** 중첩 DTO: 엔티티 타입 파라미터의 getter 로 초기화 */
    private static final String AUTHOR_DTO = """
            public class AuthorDto {
                public String name;
                public static AuthorDto from(User user) { AuthorDto d = new AuthorDto(); d.name = user.getNickname(); return d; }
            }
            """;
    /** 프록시 id 만 읽는 DTO (람다에서 static 호출) */
    private static final String ID_DTO = """
            public class ReportIdResponse {
                public Long postId; public Long reporterId;
                public static ReportIdResponse of(Report report) {
                    ReportIdResponse r = new ReportIdResponse();
                    r.postId = report.getPost().getId();
                    r.reporterId = report.getReporter().getId();
                    return r;
                }
            }
            """;
    private static final String SERVICE = """
            @Service
            public class ReportService {
                private ReportRepository reportRepository;
                public List<ReportSummaryResponse> recent() {
                    return reportRepository.findTop20ByOrderByIdDesc().stream().map(ReportSummaryResponse::from).toList();
                }
                public List<ReportIdResponse> recentIds() {
                    return reportRepository.findTop20ByOrderByIdDesc().stream().map(r -> ReportIdResponse.of(r)).toList();
                }
            }
            """;
    private static final String CONTROLLER = """
            @RestController
            public class ReportController {
                private ReportService reportService;
                @GetMapping("/recent")     public Object recent()    { return ResponseEntity.ok(reportService.recent()); }
                @GetMapping("/recent-ids") public Object recentIds() { return reportService.recentIds(); }
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

    private static DependencyGraphBuilder project() throws Exception {
        return build(USER, POST, REPORT, REPORT_REPO, SUMMARY_DTO, AUTHOR_DTO, ID_DTO, SERVICE, CONTROLLER);
    }

    private static List<AffectedApi> analyze(DependencyGraphBuilder b, String sql) {
        return ImpactAnalyzer.of(b).analyze(new SqlMigrationParser().parseSQL(sql).changes()).getAffectedApis();
    }

    private static Set<String> apis(DependencyGraphBuilder b, String sql) {
        Set<String> ids = new TreeSet<>();
        analyze(b, sql).forEach(a -> ids.add(a.getApiId()));
        return ids;
    }

    @Test
    void methodReferenceToDtoThatInitializesPostIsDetected() throws Exception {
        DependencyGraphBuilder b = project();
        assertEquals(Set.of("GET /recent"), apis(b, "ALTER TABLE posts RENAME COLUMN title TO subject;"));

        AffectedApi recent = analyze(b, "ALTER TABLE posts RENAME COLUMN title TO subject;").get(0);
        assertEquals(List.of("col:posts.title", "load:Post", "helper:ReportSummaryResponse.from",
                        "svc:ReportService.recent", "ctrl:ReportController.recent", "api:GET /recent"),
                recent.getImpactPath());
        assertEquals(NodeType.HELPER, recent.getImpactNodes().get(2).getType());
    }

    @Test
    void nestedDtoWithEntityParameterIsDetected() throws Exception {
        // ReportSummaryResponse.from → AuthorDto.from(User user) → user.getNickname()
        AffectedApi a = analyze(project(), "ALTER TABLE users DROP COLUMN email;").stream()
                .filter(x -> x.getApiId().equals("GET /recent")).findFirst().orElseThrow();
        assertTrue(a.getImpactPath().contains("load:User"), a.getImpactPath().toString());
    }

    @Test
    void dtoReadingOnlyProxyIdsIsNotAffected() throws Exception {
        DependencyGraphBuilder b = project();
        assertFalse(apis(b, "ALTER TABLE posts RENAME COLUMN title TO subject;").contains("GET /recent-ids"));
        assertFalse(apis(b, "ALTER TABLE users DROP COLUMN email;").contains("GET /recent-ids"));
    }

    @Test
    void ownTableChangeStillReachesBothViaRepository() throws Exception {
        assertEquals(Set.of("GET /recent", "GET /recent-ids"),
                apis(project(), "ALTER TABLE reports DROP COLUMN reason;"));
    }

    @Test
    void staticCallsToUnknownClassesAreIgnored() throws Exception {
        // ResponseEntity.ok(...), List.of 등은 helper 로 알려지지 않았으므로 노드를 만들지 않는다.
        DependencyGraphBuilder b = project();
        assertNull(b.getNodeIndex().get("helper:ResponseEntity.ok"));
        assertNotNull(b.getNodeIndex().get("helper:ReportIdResponse.of"));
    }

    @Test
    void parserCollectsHelperStaticMethodsOnly() throws Exception {
        List<ServiceInfo> infos = new ServiceParser().parseSource(SUMMARY_DTO);
        assertEquals(1, infos.size());
        ServiceInfo dto = infos.get(0);
        assertTrue(dto.isHelper());
        assertEquals(List.of("from:post"), dto.getAssociationAccesses().stream()
                .map(a -> a.callerMethod() + ":" + a.attribute()).toList());
        assertEquals(List.of("AuthorDto.from"), dto.getStaticCalls().stream()
                .map(c -> c.calleeClass() + "." + c.calleeMethod()).toList());

        ServiceInfo author = new ServiceParser().parseSource(AUTHOR_DTO).get(0);
        assertEquals(List.of("User"), author.getEntityTypeAccesses().stream()
                .map(ServiceInfo.EntityTypeAccess::entityType).toList());
    }
}
