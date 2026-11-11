package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 연관 엔티티 로딩 전파 (실제 MySQL 측정에서 놓친 10건의 원인).
 *
 * JPA 는 연관 엔티티를 로딩할 때 그 엔티티의 컬럼을 전부 SELECT 한다. 그래서 users.email 을 지우면
 * User 를 직접 조회하지 않는 API 도 Post.author 를 로딩하는 순간 실패한다.
 * LAZY 연관은 실제로 로딩되는 신호(@EntityGraph, join fetch, getId() 가 아닌 getter 접근)가 있을 때만 전파한다.
 */
class AssociationLoadTest {

    // ── 픽스처: users ← posts.author (LAZY), posts ← reports.post (LAZY) ──────

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
            }
            """;
    private static final String POST_REPO = """
            public interface PostRepository extends JpaRepository<Post, Long> {
                @EntityGraph(attributePaths = {"author"})
                List<Post> findAllByOrderByIdDesc();
                long countByTitle(String title);
            }
            """;
    private static final String REPORT_REPO = """
            public interface ReportRepository extends JpaRepository<Report, Long> {
                @Query("select r from Report r join fetch r.post p join fetch p.author where r.reason = :x")
                List<Report> findFull(String x);
                List<Report> findByReason(String reason);
            }
            """;
    private static final String SERVICES = """
            @Service
            public class FeedService {
                private PostRepository postRepository;
                private ReportRepository reportRepository;
                public Object feed() { return postRepository.findAllByOrderByIdDesc(); }
                public long count() { return postRepository.countByTitle("x"); }
                public Object full() { return reportRepository.findFull("x"); }
                public Object titles() {
                    return reportRepository.findByReason("x").stream().map(r -> r.getPost().getTitle()).toList();
                }
                public Object ids() {
                    return reportRepository.findByReason("x").stream().map(r -> r.getPost().getId()).toList();
                }
            }
            """;
    private static final String CONTROLLER = """
            @RestController
            public class FeedController {
                private FeedService feedService;
                @GetMapping("/feed")   public Object feed()   { return feedService.feed(); }
                @GetMapping("/count")  public Object count()  { return feedService.count(); }
                @GetMapping("/full")   public Object full()   { return feedService.full(); }
                @GetMapping("/titles") public Object titles() { return feedService.titles(); }
                @GetMapping("/ids")    public Object ids()    { return feedService.ids(); }
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
        List<SchemaChange> changes = new MigrationParser().parseSQL(sql).changes();
        Set<String> ids = new TreeSet<>();
        new ImpactAnalyzer(b.getGraph(), b.getNodeIndex(), b.getFkFieldsReferencingTable())
                .analyze(changes).getAffectedApis().forEach(a -> ids.add(a.getApiId()));
        return ids;
    }

    private static DependencyGraphBuilder project() throws Exception {
        return build(USER, POST, REPORT, POST_REPO, REPORT_REPO, SERVICES, CONTROLLER);
    }

    // ── 전파되는 경우 ────────────────────────────────────────────────────────

    @Test
    void entityGraphLoadsAuthorSoUserColumnDropReachesFeed() throws Exception {
        Set<String> hit = apis(project(), "ALTER TABLE users DROP COLUMN email;");
        assertTrue(hit.contains("GET /feed"), hit.toString());       // @EntityGraph("author")
        assertTrue(hit.contains("GET /full"), hit.toString());       // join fetch r.post p join fetch p.author
    }

    @Test
    void lazyGetterAccessInServiceLoadsAssociation() throws Exception {
        Set<String> hit = apis(project(), "ALTER TABLE posts RENAME COLUMN title TO subject;");
        assertTrue(hit.contains("GET /titles"), hit.toString());     // r.getPost().getTitle()
        assertTrue(hit.contains("GET /full"), hit.toString());       // join fetch r.post
    }

    @Test
    void impactPathShowsAssociationLoadNode() throws Exception {
        List<SchemaChange> changes = new MigrationParser().parseSQL("ALTER TABLE users DROP COLUMN email;").changes();
        DependencyGraphBuilder b = project();
        AffectedApi feed = new ImpactAnalyzer(b.getGraph(), b.getNodeIndex(), b.getFkFieldsReferencingTable())
                .analyze(changes).getAffectedApis().stream()
                .filter(a -> a.getApiId().equals("GET /feed")).findFirst().orElseThrow();
        assertEquals(List.of("col:users.email", "load:User", "repo:PostRepository.findAllByOrderByIdDesc",
                        "svc:FeedService.feed", "ctrl:FeedController.feed", "api:GET /feed"),
                feed.getImpactPath());
    }

    // ── 전파되지 않는 경우 (실제 측정에서 오탐이던 패턴) ─────────────────────

    @Test
    void lazyAssociationWithoutLoadingSignalIsNotPropagated() throws Exception {
        Set<String> hit = apis(project(), "ALTER TABLE users DROP COLUMN email;");
        assertFalse(hit.contains("GET /count"), hit.toString());     // count 쿼리, author 로딩 없음
        assertFalse(hit.contains("GET /titles"), hit.toString());    // Post 만 초기화, author 는 아님
    }

    @Test
    void getIdOnProxyDoesNotLoadAssociation() throws Exception {
        Set<String> hit = apis(project(), "ALTER TABLE posts RENAME COLUMN title TO subject;");
        assertFalse(hit.contains("GET /ids"), hit.toString());       // r.getPost().getId()
    }

    @Test
    void typeAndConstraintChangesDoNotFollowAssociationLoads() throws Exception {
        DependencyGraphBuilder b = project();
        assertEquals(Set.of(), apis(b, "ALTER TABLE users MODIFY email VARCHAR(50);"));
        assertEquals(Set.of(), apis(b, "ALTER TABLE users MODIFY nickname VARCHAR(50) NOT NULL;"));
    }

    @Test
    void eagerAssociationIsLoadedByEveryOwnerRepositoryMethod() throws Exception {
        String eagerPost = POST.replace("@ManyToOne(fetch = FetchType.LAZY)", "@ManyToOne");
        Set<String> hit = apis(build(USER, eagerPost, REPORT, POST_REPO, REPORT_REPO, SERVICES, CONTROLLER),
                "ALTER TABLE users DROP COLUMN email;");
        assertTrue(hit.contains("GET /count"), hit.toString());      // EAGER: Post 를 읽으면 author 도 따라온다
        assertTrue(hit.contains("GET /titles"), hit.toString());     // load:Post → load:User (EAGER 연쇄)
    }

    // ── 파서 ─────────────────────────────────────────────────────────────────

    @Test
    void repositoryParserReadsEntityGraphAndJoinFetchPaths() throws Exception {
        RepositoryInfo posts = new RepositoryParser().parseSource(POST_REPO).get(0);
        assertEquals(List.of("author"), posts.getMethods().get(0).fetchPaths());
        assertEquals(List.of(), posts.getMethods().get(1).fetchPaths());

        RepositoryInfo reports = new RepositoryParser().parseSource(REPORT_REPO).get(0);
        assertEquals(List.of("post", "post.author"), reports.getMethods().get(0).fetchPaths());
    }

    @Test
    void entityParserReadsFetchType() throws Exception {
        FkMapping lazy = new EntityParser().parseSource(POST).fkMappings().get(0);
        assertFalse(lazy.isEagerFetch());
        FkMapping eager = new EntityParser().parseSource(POST.replace("(fetch = FetchType.LAZY)", ""))
                .fkMappings().get(0);
        assertTrue(eager.isEagerFetch());                            // @ManyToOne 기본값
    }

    @Test
    void serviceParserRecordsOnlyLoadingGetterChains() throws Exception {
        ServiceInfo svc = new ServiceParser().parseSource(SERVICES).get(0);
        List<String> accesses = svc.getAssociationAccesses().stream()
                .map(a -> a.callerMethod() + ":" + a.attribute()).toList();
        assertEquals(List.of("titles:post"), accesses);              // ids() 의 getPost().getId() 는 제외
    }

    @Test
    void mappedByOneToManyDoesNotHideFollowingFields() throws Exception {
        // 예전에는 mappedBy @OneToMany 를 만나면 return 해서 그 뒤 필드가 전부 누락됐다.
        EntityParser.ParseResult pr = new EntityParser().parseSource("""
                @Entity @Table(name = "users")
                public class User {
                    @Id private Long id;
                    @OneToMany(mappedBy = "author") private List<Post> posts;
                    @Column(name = "email") private String email;
                }
                """);
        assertTrue(pr.entityMappings().stream().anyMatch(m -> m.getColumnName().equals("email")),
                pr.entityMappings().toString());
    }
}
