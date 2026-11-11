package schemaguard.graph;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파싱 결과를 하나의 방향성 의존성 그래프로 통합한다.
 *
 * sourceRoot 를 받아 절대경로를 소스 루트 기준 상대경로로 정규화한다.
 * ex. "D:/project/src/main/java/com/example/UserRepository.java"
 *   → "src/main/java/com/example/UserRepository.java"
 */
public class DependencyGraphBuilder {

    private final DefaultDirectedGraph<Node, Edge> graph =
            new DefaultDirectedGraph<>(Edge.class);
    private final Map<String, Node> nodeIndex = new HashMap<>();

    /**
     * 테이블명 → 그 테이블을 FK로 참조하는 다른 엔티티의 FK 필드 노드.
     * 예) tags → [PostTag.tag]. 테이블 삭제/이름 변경이 참조하는 쪽 API까지 번지게 하는 데 쓴다.
     */
    private final Map<String, List<Node>> fkFieldsReferencingTable = new HashMap<>();

    /** CLI 에서 전달받는 --source 경로 (src/main/java 의 부모 또는 그 자체) */
    private String sourceRoot = "";

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = normalizeSlash(new File(sourceRoot).getAbsolutePath());
    }

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public DefaultDirectedGraph<Node, Edge> build(
            List<EntityMapping>  entities,
            List<FkMapping>      fkMappings,
            List<RepositoryInfo> repos,
            List<ServiceInfo>    services,
            List<ControllerInfo> controllers) {

        addEntityEdges(entities);
        addFkEdges(fkMappings);
        indexFkReferences(entities, fkMappings);
        addRepositoryEdges(repos, entities, fkMappings);
        addServiceEdges(services);
        addControllerEdges(controllers);
        addAssociationLoadEdges(entities, fkMappings, repos, services);
        return graph;
    }

    public DefaultDirectedGraph<Node, Edge> getGraph() { return graph; }
    public Map<String, Node> getNodeIndex()            { return nodeIndex; }
    public Map<String, List<Node>> getFkFieldsReferencingTable() { return fkFieldsReferencingTable; }

    // ── 1단계: 일반 Entity Field → Column ────────────────────────────────────

    private void addEntityEdges(List<EntityMapping> entities) {
        for (EntityMapping em : entities) {
            Node field  = getOrCreate(em.getFieldNodeId(),  NodeType.FIELD,
                    em.getEntityClass() + "." + em.getFieldName(),
                    em.getFilePath(), em.getLineNumber());
            Node column = getOrCreate(em.getColumnNodeId(), NodeType.COLUMN,
                    em.getTableName() + "." + em.getColumnName(), null, -1);
            addEdge(field, column, RelationType.MAPS);
        }
    }

    // ── 2단계: FK_FIELD → Column ──────────────────────────────────────────────

    private void addFkEdges(List<FkMapping> fkMappings) {
        for (FkMapping fk : fkMappings) {
            Node fkField = getOrCreate(fk.getFkFieldNodeId(), NodeType.FK_FIELD,
                    fk.getEntityClass() + "." + fk.getFieldName()
                    + " (@" + fk.getFkRelationType() + " -> " + fk.getReferencedEntity() + ")",
                    fk.getFilePath(), fk.getLineNumber());
            Node column  = getOrCreate(fk.getColumnNodeId(), NodeType.COLUMN,
                    fk.getTableName() + "." + fk.getJoinColumnName(), null, -1);
            addEdge(fkField, column, RelationType.FK_MAPS);
        }
    }

    /** FK 필드가 참조하는 엔티티 → 그 엔티티의 테이블을 찾아 "테이블 → 참조하는 FK 필드" 색인을 만든다. */
    private void indexFkReferences(List<EntityMapping> entities, List<FkMapping> fkMappings) {
        Map<String, String> tableOfEntity = new HashMap<>();
        for (EntityMapping em : entities) tableOfEntity.putIfAbsent(em.getEntityClass(), em.getTableName());
        for (FkMapping fk : fkMappings)   tableOfEntity.putIfAbsent(fk.getEntityClass(), fk.getTableName());

        for (FkMapping fk : fkMappings) {
            String referencedTable = tableOfEntity.get(fk.getReferencedEntity());
            Node fkNode = nodeIndex.get(fk.getFkFieldNodeId());
            if (referencedTable == null || fkNode == null) continue;

            List<Node> list = fkFieldsReferencingTable.computeIfAbsent(referencedTable, t -> new ArrayList<>());
            if (!list.contains(fkNode)) list.add(fkNode);
        }
    }

    // ── 3단계: Repository → Field / FK_FIELD ─────────────────────────────────

    private void addRepositoryEdges(List<RepositoryInfo> repos,
                                    List<EntityMapping>  entities,
                                    List<FkMapping>      fkMappings) {
        // 엔티티 클래스별로 한 번만 묶는다. 예전에는 리포지토리마다 전체 매핑 목록을 훑었다
        // (리포지토리 수 × 전체 필드 수).
        Map<String, List<EntityMapping>> fieldsByEntity = new HashMap<>();
        for (EntityMapping em : entities)
            fieldsByEntity.computeIfAbsent(em.getEntityClass(), k -> new ArrayList<>()).add(em);
        Map<String, List<FkMapping>> fkFieldsByEntity = new HashMap<>();
        for (FkMapping fk : fkMappings)
            fkFieldsByEntity.computeIfAbsent(fk.getEntityClass(), k -> new ArrayList<>()).add(fk);

        for (RepositoryInfo repo : repos) {
            String entityClass = repo.getEntityClass();

            List<EntityMapping> relatedFields = fieldsByEntity.getOrDefault(entityClass, List.of());
            List<FkMapping> relatedFkFields = fkFieldsByEntity.getOrDefault(entityClass, List.of());

            for (RepositoryInfo.MethodEntry entry : methodsWithDefaults(repo)) {
                Node repoNode = getOrCreate(
                        "repo:" + repo.getClassName() + "." + entry.name(),
                        NodeType.REPOSITORY,
                        repo.getClassName() + "." + entry.name(),
                        repo.getFilePath(), entry.lineNumber());

                for (EntityMapping em : relatedFields) {
                    Node fieldNode = getOrCreate(em.getFieldNodeId(), NodeType.FIELD,
                            em.getEntityClass() + "." + em.getFieldName(),
                            em.getFilePath(), em.getLineNumber());
                    addEdge(repoNode, fieldNode, RelationType.USES);
                }
                for (FkMapping fk : relatedFkFields) {
                    Node fkNode = getOrCreate(fk.getFkFieldNodeId(), NodeType.FK_FIELD,
                            fk.getEntityClass() + "." + fk.getFieldName(),
                            fk.getFilePath(), fk.getLineNumber());
                    addEdge(repoNode, fkNode, RelationType.USES);
                }
            }
        }
    }

    private static final List<String> DEFAULT_CRUD =
            List.of("findById", "findAll", "save", "delete", "deleteById", "existsById");

    /** 선언 메서드 + (선언되지 않은) 기본 CRUD 메서드 */
    private static List<RepositoryInfo.MethodEntry> methodsWithDefaults(RepositoryInfo repo) {
        List<RepositoryInfo.MethodEntry> all = new ArrayList<>(repo.getMethods());
        for (String def : DEFAULT_CRUD) {
            if (all.stream().noneMatch(e -> e.name().equals(def)))
                all.add(new RepositoryInfo.MethodEntry(def, repo.getClassLine()));
        }
        return all;
    }

    private Node repoMethodNode(RepositoryInfo repo, RepositoryInfo.MethodEntry entry) {
        return getOrCreate("repo:" + repo.getClassName() + "." + entry.name(), NodeType.REPOSITORY,
                repo.getClassName() + "." + entry.name(), repo.getFilePath(), entry.lineNumber());
    }

    // ── 6단계: 연관 엔티티 로딩 ──────────────────────────────────────────────

    /**
     * JPA 는 연관 엔티티를 로딩할 때 그 엔티티에 매핑된 컬럼을 전부 SELECT 한다.
     * 그래서 users.email 을 지우면 User 를 직접 조회하지 않아도, Post.author 를 함께 로딩하는
     * 게시글 목록 API 가 실패한다. 이 경로를 "load:&lt;Entity&gt;" 노드로 표현한다.
     *
     *   load:User ──LOADS──▶ col:users.*            (로딩하면 모든 컬럼을 읽는다)
     *   repo:PostRepository.findAll ──LOADS──▶ load:User   (@EntityGraph("author"), join fetch)
     *   repo:PostRepository.* ──LOADS──▶ load:User          (Post.author 가 EAGER 인 경우)
     *   load:Post ──LOADS──▶ load:User                      (Post 를 연관으로 로딩해도 EAGER author 가 따라온다)
     *   svc:ReportService.process ──LOADS──▶ load:Post      (report.getPost().getTitle() 로 지연 로딩 초기화)
     *
     * LAZY 연관은 위 신호가 있을 때만 연결한다. getId() 만 꺼내는 접근은 프록시가 id 를 갖고 있어 로딩되지 않는다.
     * ImpactAnalyzer 는 컬럼/테이블 삭제·이름 변경일 때만 LOADS 엣지를 따라간다.
     */
    private void addAssociationLoadEdges(List<EntityMapping> entities, List<FkMapping> fkMappings,
                                         List<RepositoryInfo> repos, List<ServiceInfo> services) {
        Map<String, FkMapping> fkByOwnerAndField = new HashMap<>();
        Map<String, List<FkMapping>> fkByField = new HashMap<>();
        for (FkMapping fk : fkMappings) {
            fkByOwnerAndField.putIfAbsent(fk.getEntityClass() + "#" + fk.getFieldName(), fk);
            fkByField.computeIfAbsent(fk.getFieldName(), k -> new ArrayList<>()).add(fk);
        }
        Map<String, List<EntityMapping>> fieldsByEntity = new HashMap<>();
        for (EntityMapping em : entities)
            fieldsByEntity.computeIfAbsent(em.getEntityClass(), k -> new ArrayList<>()).add(em);
        Map<String, List<FkMapping>> ownFkByEntity = new HashMap<>();
        for (FkMapping fk : fkMappings)
            if (FkMapping.defaultEager(fk.getFkRelationType()))      // 이 테이블에 FK 컬럼이 있는 쪽
                ownFkByEntity.computeIfAbsent(fk.getEntityClass(), k -> new ArrayList<>()).add(fk);

        Map<String, Node> loadNodes = new HashMap<>();
        java.util.function.Function<String, Node> load = entity -> loadNodes.computeIfAbsent(entity, e -> {
            Node n = getOrCreate("load:" + e, NodeType.ENTITY_LOAD, e + " (association load)", null, -1);
            for (EntityMapping em : fieldsByEntity.getOrDefault(e, List.of()))
                addEdge(n, getOrCreate(em.getColumnNodeId(), NodeType.COLUMN,
                        em.getTableName() + "." + em.getColumnName(), null, -1), RelationType.LOADS);
            for (FkMapping fk : ownFkByEntity.getOrDefault(e, List.of()))
                addEdge(n, getOrCreate(fk.getColumnNodeId(), NodeType.COLUMN,
                        fk.getTableName() + "." + fk.getJoinColumnName(), null, -1), RelationType.LOADS);
            return n;
        });
        // 매핑 정보가 있는 엔티티만 대상 (외부 라이브러리 타입 등은 제외)
        java.util.function.Predicate<String> known = e -> fieldsByEntity.containsKey(e) || ownFkByEntity.containsKey(e);

        // EAGER: 소유 엔티티를 로딩하는 모든 곳에서 연관 엔티티도 로딩된다.
        Map<String, List<RepositoryInfo>> reposByEntity = new HashMap<>();
        for (RepositoryInfo r : repos) reposByEntity.computeIfAbsent(r.getEntityClass(), k -> new ArrayList<>()).add(r);
        for (FkMapping fk : fkMappings) {
            if (!fk.isEagerFetch() || !known.test(fk.getReferencedEntity())) continue;
            Node target = load.apply(fk.getReferencedEntity());
            addEdge(load.apply(fk.getEntityClass()), target, RelationType.LOADS);
            for (RepositoryInfo repo : reposByEntity.getOrDefault(fk.getEntityClass(), List.of()))
                for (RepositoryInfo.MethodEntry m : methodsWithDefaults(repo))
                    addEdge(repoMethodNode(repo, m), target, RelationType.LOADS);
        }

        // @EntityGraph / join fetch: 경로를 따라가며 로딩되는 엔티티마다 연결한다. "post.author" → Post, User
        for (RepositoryInfo repo : repos) {
            for (RepositoryInfo.MethodEntry m : repo.getMethods()) {
                for (String path : m.fetchPaths()) {
                    String owner = repo.getEntityClass();
                    for (String attr : path.split("\\.")) {
                        FkMapping fk = fkByOwnerAndField.get(owner + "#" + attr);
                        if (fk == null || !known.test(fk.getReferencedEntity())) break;
                        addEdge(repoMethodNode(repo, m), load.apply(fk.getReferencedEntity()), RelationType.LOADS);
                        owner = fk.getReferencedEntity();
                    }
                }
            }
        }

        // 서비스의 지연 로딩 초기화: 같은 이름의 FK 필드가 모두 같은 엔티티를 가리킬 때만 연결한다.
        for (ServiceInfo svc : services) {
            for (ServiceInfo.AssociationAccess a : svc.getAssociationAccesses()) {
                List<FkMapping> candidates = fkByField.getOrDefault(a.attribute(), List.of());
                Set<String> refs = new java.util.TreeSet<>();
                candidates.forEach(fk -> refs.add(fk.getReferencedEntity()));
                if (refs.size() != 1 || !known.test(refs.iterator().next())) continue;
                Node svcNode = getOrCreate("svc:" + svc.getClassName() + "." + a.callerMethod(),
                        NodeType.SERVICE, svc.getClassName() + "." + a.callerMethod(),
                        svc.getFilePath(), a.callerLine());
                addEdge(svcNode, load.apply(refs.iterator().next()), RelationType.LOADS);
            }
        }
    }

    // ── 4단계: Service → Repository ──────────────────────────────────────────

    private void addServiceEdges(List<ServiceInfo> services) {
        for (ServiceInfo svc : services) {
            for (ServiceInfo.MethodCall call : svc.getMethodCalls()) {
                Node svcNode = getOrCreate(
                        "svc:" + svc.getClassName() + "." + call.callerMethod,
                        NodeType.SERVICE,
                        svc.getClassName() + "." + call.callerMethod,
                        svc.getFilePath(), call.callerLine);

                NodeType calleeType   = call.calleeClass.contains("Repository")
                        ? NodeType.REPOSITORY : NodeType.SERVICE;
                String   calleePrefix = calleeType == NodeType.REPOSITORY ? "repo:" : "svc:";

                // callee 노드는 이미 위에서 만들어져 있을 수 있으므로 getOrCreate
                // 위치 정보는 calleeLine 기준 (Repository 파일 경로는 아직 모름 → null)
                Node calleeNode = getOrCreate(
                        calleePrefix + call.calleeClass + "." + call.calleeMethod,
                        calleeType,
                        call.calleeClass + "." + call.calleeMethod,
                        null, call.calleeLine);

                addEdge(svcNode, calleeNode, RelationType.CALLS);
            }
        }
    }

    // ── 5단계: API → Controller → Service ────────────────────────────────────

    private void addControllerEdges(List<ControllerInfo> controllers) {
        for (ControllerInfo ctrl : controllers) {
            for (ControllerInfo.EndpointMapping ep : ctrl.getEndpoints()) {
                Node apiNode  = getOrCreate(ep.apiNodeId(), NodeType.API,
                        ep.httpMethod + " " + ep.path, null, -1);
                Node ctrlNode = getOrCreate(ep.controllerNodeId(ctrl.getClassName()),
                        NodeType.CONTROLLER,
                        ctrl.getClassName() + "." + ep.controllerMethod,
                        ctrl.getFilePath(), ep.methodLine);

                addEdge(apiNode, ctrlNode, RelationType.MAPS);

                for (ControllerInfo.ServiceCall call : ep.serviceCalls) {
                    Node svcNode = getOrCreate(
                            "svc:" + call.serviceClass() + "." + call.serviceMethod(),
                            NodeType.SERVICE,
                            call.serviceClass() + "." + call.serviceMethod(),
                            null, call.line());
                    addEdge(ctrlNode, svcNode, RelationType.CALLS);
                }
            }
        }
    }

    // ── 그래프 유틸 ──────────────────────────────────────────────────────────

    /**
     * 노드를 만들거나 기존 노드를 돌려준다.
     *
     * 같은 노드가 여러 곳에서 언급된다. 예) svc:B.m 은 B 자신의 정의(파일+라인)로도,
     * A 의 호출 지점(파일 없음, 호출 라인)으로도 만들어질 수 있다. 예전에는 먼저 만든 쪽이 이겨서
     * 파일 처리 순서에 따라 위치가 달라졌다. 이제는 순서와 무관하게
     *   1) 파일 경로가 있는 위치가 없는 위치보다 우선하고
     *   2) 둘 다 있으면 (경로, 라인)이 작은 쪽을 남긴다.
     */
    private Node getOrCreate(String id, NodeType type, String name,
                              String rawFilePath, int lineNumber) {
        String relativePath = toRelativePath(rawFilePath);
        Node existing = nodeIndex.get(id);
        if (existing == null) {
            Node n = new Node(id, type, name, relativePath, lineNumber);
            graph.addVertex(n);
            nodeIndex.put(id, n);
            return n;
        }
        if (prefersLocation(relativePath, lineNumber, existing.getFilePath(), existing.getLineNumber())) {
            existing.setFilePath(relativePath);
            existing.setLineNumber(lineNumber);
        }
        return existing;
    }

    /** 위치의 전순서: 경로 있음 &lt; 경로 없음, 경로 사전순, 유효 라인(&gt;0) 작은 순, 라인 없음(-1)은 맨 뒤. */
    private static boolean prefersLocation(String newPath, int newLine, String oldPath, int oldLine) {
        if ((newPath == null) != (oldPath == null)) return newPath != null;
        if (newPath != null) {
            int c = newPath.compareTo(oldPath);
            if (c != 0) return c < 0;
        }
        int n = newLine > 0 ? newLine : Integer.MAX_VALUE;
        int o = oldLine > 0 ? oldLine : Integer.MAX_VALUE;
        return n < o;
    }

    private void addEdge(Node from, Node to, RelationType relation) {
        if (!graph.containsEdge(from, to))
            graph.addEdge(from, to, new Edge(relation));
    }

    // ── 경로 정규화 ───────────────────────────────────────────────────────────

    /**
     * 절대경로를 sourceRoot 기준 상대경로로 변환한다.
     * ex. "D:/project/src/main/java/..." → "src/main/java/..."
     *
     * sourceRoot 가 "D:/project/src/main/java" 로 지정된 경우
     * 그 위의 "src/main/java" 부분도 포함하도록 sourceRoot 의 부모를 거슬러 올라간다.
     */
    private String toRelativePath(String rawFilePath) {
        if (rawFilePath == null) return null;
        String normalized = normalizeSlash(rawFilePath);

        // sourceRoot 를 기준으로 잘라낸다
        if (!sourceRoot.isEmpty() && normalized.startsWith(sourceRoot)) {
            String rel = normalized.substring(sourceRoot.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }

        // sourceRoot 에 "src/main/java" 가 포함된 경우 그 앞까지 잘라낸다
        int idx = normalized.indexOf("/src/main/java/");
        if (idx >= 0) return normalized.substring(idx + 1); // "src/main/java/..."

        return normalized; // 변환 불가 시 원본 반환
    }

    private String normalizeSlash(String path) {
        return path == null ? "" : path.replace("\\", "/");
    }
}