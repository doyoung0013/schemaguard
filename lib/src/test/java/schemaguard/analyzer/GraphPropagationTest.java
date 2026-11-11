package schemaguard.analyzer;

import org.junit.jupiter.api.Test;
import schemaguard.graph.DependencyGraphBuilder;
import schemaguard.model.*;
import schemaguard.parser.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * community 프로젝트에 SchemaGuard를 돌려 발견한 두 가지 누락을 재현하는 테스트.
 *   1) 컨트롤러 메서드가 서비스를 여러 개 호출해도 첫 호출만 그래프에 연결되던 문제
 *   2) 테이블 삭제/이름 변경이 그 테이블을 FK로 참조하는 다른 엔티티의 API로 번지지 않던 문제
 */
class GraphPropagationTest {

    private record Project(DependencyGraphBuilder builder) {}

    private static Project build(String... sources) throws Exception {
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
        return new Project(b);
    }

    private static Set<String> apis(DependencyGraphBuilder b, boolean propagateFk, String sql) {
        List<SchemaChange> changes = new MigrationParser().parseSQL(sql).changes();
        ImpactAnalyzer analyzer = propagateFk
                ? new ImpactAnalyzer(b.getGraph(), b.getNodeIndex(), b.getFkFieldsReferencingTable())
                : new ImpactAnalyzer(b.getGraph(), b.getNodeIndex());
        Set<String> ids = new TreeSet<>();
        analyzer.analyze(changes).getAffectedApis().forEach(a -> ids.add(a.getApiId()));
        return ids;
    }

    // ── 1. 컨트롤러의 서비스 호출 여러 개 ────────────────────────────────────

    private static final String NOTE = """
            @Entity @Table(name = "notes")
            public class Note { @Id private Long id; @Column(name = "body") private String body; }
            """;
    private static final String NOTE_REPO = """
            public interface NoteRepository extends JpaRepository<Note, Long> { Note findByBody(String b); }
            """;
    private static final String NOTE_SERVICE = """
            @Service
            public class NoteService {
                private NoteRepository noteRepository;
                public Note find() { return noteRepository.findByBody("x"); }
            }
            """;
    private static final String PAGE_CONTROLLER = """
            @RestController
            public class PageController {
                private HelloService helloService;
                private NoteService noteService;

                @GetMapping("/page")
                public Object page() {
                    helloService.hello();
                    helloService.hello();
                    return noteService.find();
                }
            }
            """;

    @Test
    void controllerParserKeepsEveryDistinctServiceCall() throws Exception {
        ControllerInfo info = new ControllerParser().parseSource(PAGE_CONTROLLER).get(0);
        ControllerInfo.EndpointMapping ep = info.getEndpoints().get(0);

        assertEquals(2, ep.serviceCalls.size());   // 같은 호출(helloService.hello)은 한 번만
        assertEquals("HelloService", ep.serviceCalls.get(0).serviceClass());
        assertEquals("NoteService", ep.serviceCalls.get(1).serviceClass());
        assertEquals("HelloService", ep.calleeServiceClass);   // 기존 필드는 첫 호출 유지
    }

    @Test
    void apiIsFoundThroughTheSecondServiceCall() throws Exception {
        Project p = build(NOTE, NOTE_REPO, NOTE_SERVICE, PAGE_CONTROLLER);

        assertEquals(Set.of("GET /page"),
                apis(p.builder(), true, "ALTER TABLE notes DROP COLUMN body;"));
    }

    // ── 2. FK로 참조하는 엔티티로 번지는 테이블 변경 ─────────────────────────

    private static final String TAG = """
            @Entity @Table(name = "tags")
            public class Tag { @Id private Long id; @Column(name = "use_count") private long useCount; }
            """;
    private static final String POST_TAG = """
            @Entity @Table(name = "post_tags")
            public class PostTag {
                @Id private Long id;
                @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "tag_id") private Tag tag;
            }
            """;
    private static final String POST_TAG_REPO = """
            public interface PostTagRepository extends JpaRepository<PostTag, Long> {
                java.util.List<PostTag> findByPostId(Long postId);
            }
            """;
    private static final String TAG_SERVICE = """
            @Service
            public class TagService {
                private PostTagRepository postTagRepository;
                public Object namesOf(Long id) { return postTagRepository.findByPostId(id); }
            }
            """;
    private static final String TAG_CONTROLLER = """
            @RestController
            public class TagController {
                private TagService tagService;
                @GetMapping("/tags/of/{id}")
                public Object namesOf(Long id) { return tagService.namesOf(id); }
            }
            """;

    @Test
    void renameTableReachesApisOfEntitiesThatReferenceItByForeignKey() throws Exception {
        Project p = build(TAG, POST_TAG, POST_TAG_REPO, TAG_SERVICE, TAG_CONTROLLER);
        String sql = "RENAME TABLE tags TO hashtags;";

        assertEquals(Set.of("GET /tags/of/{id}"), apis(p.builder(), true, sql));
        // 전파가 없던 기존 동작: tags 테이블의 컬럼/FK만 시작점이라 이 API를 놓친다.
        assertEquals(Set.of(), apis(p.builder(), false, sql));
    }

    @Test
    void dropTableAlsoReachesReferencingEntities() throws Exception {
        Project p = build(TAG, POST_TAG, POST_TAG_REPO, TAG_SERVICE, TAG_CONTROLLER);

        assertEquals(Set.of("GET /tags/of/{id}"), apis(p.builder(), true, "DROP TABLE tags;"));
    }

    @Test
    void unrelatedTableChangeDoesNotPropagate() throws Exception {
        Project p = build(TAG, POST_TAG, POST_TAG_REPO, TAG_SERVICE, TAG_CONTROLLER);

        assertEquals(Set.of(), apis(p.builder(), true, "DROP TABLE notes;"));
    }

    @Test
    void fkReferenceIndexMapsReferencedTableToFkField() throws Exception {
        Project p = build(TAG, POST_TAG);

        Map<String, List<Node>> idx = p.builder().getFkFieldsReferencingTable();
        assertEquals(1, idx.get("tags").size());
        assertEquals("fkfield:PostTag.tag", idx.get("tags").get(0).getId());
        assertNull(idx.get("post_tags"));
    }
}
