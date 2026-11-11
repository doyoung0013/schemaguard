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

    /**
     * 테이블명 → 그 테이블의 엔티티를 저장하는 Repository 메서드 노드 (save, saveAll, saveAndFlush ...).
     * NOT NULL/UNIQUE 추가, 길이 축소, FK 참조 변경처럼 "쓰기만 실패하는" 변경의 시작점이다.
     */
    private final Map<String, List<Node>> writeMethodsByTable = new HashMap<>();

    /** 연관 접근 해석 결과 집계 (linked-declared / linked-inferred / linked-solver / linked-name / name-ambiguous / root-not-entity / not-association) */
    private final Map<String, Integer> resolution = new java.util.TreeMap<>();
    public Map<String, Integer> getAssociationResolution() { return resolution; }

    /** static 메서드를 가진 DTO·매퍼 클래스명 (ServiceInfo.isHelper) */
    private final Set<String> helperClasses = new java.util.HashSet<>();

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
        indexWriteMethods(entities, fkMappings, repos);
        return graph;
    }

    public DefaultDirectedGraph<Node, Edge> getGraph() { return graph; }
    public Map<String, Node> getNodeIndex()            { return nodeIndex; }
    public Map<String, List<Node>> getFkFieldsReferencingTable() { return fkFieldsReferencingTable; }
    public Map<String, List<Node>> getWriteMethodsByTable()      { return writeMethodsByTable; }

    /** 그래프가 다 만들어진 뒤, 서비스가 호출하는 save* 메서드(선언/기본/상속)까지 포함해 색인한다. */
    private void indexWriteMethods(List<EntityMapping> entities, List<FkMapping> fkMappings,
                                   List<RepositoryInfo> repos) {
        Map<String, String> tableOfEntity = new HashMap<>();
        for (EntityMapping em : entities) tableOfEntity.putIfAbsent(em.getEntityClass(), em.getTableName());
        for (FkMapping fk : fkMappings)   tableOfEntity.putIfAbsent(fk.getEntityClass(), fk.getTableName());

        // 리포지토리 클래스명 → 테이블. nodeIndex 는 한 번만 훑는다 (리포지토리마다 훑으면 리포지토리 수 × 노드 수).
        Map<String, String> tableOfRepo = new HashMap<>();
        for (RepositoryInfo repo : repos) {
            String table = tableOfEntity.get(repo.getEntityClass());
            if (table != null) tableOfRepo.putIfAbsent(repo.getClassName(), table);
        }
        for (Map.Entry<String, Node> e : nodeIndex.entrySet()) {
            String id = e.getKey();                              // "repo:<Repository>.<method>"
            if (!id.startsWith("repo:")) continue;
            int dot = id.indexOf('.', 5);
            if (dot < 0 || !id.startsWith(".save", dot)) continue;
            String table = tableOfRepo.get(id.substring(5, dot));
            if (table != null) writeMethodsByTable.computeIfAbsent(table, t -> new ArrayList<>()).add(e.getValue());
        }
    }

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

        Map<String, String> entityOfRepo = new HashMap<>();
        for (RepositoryInfo r : repos) entityOfRepo.putIfAbsent(r.getClassName(), r.getEntityClass());

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

        // 서비스·DTO 의 지연 로딩 초기화.
        //   루트 타입을 알면(선언/symbol solver) FK 매핑을 따라 경로를 정확히 해석한다. 루트가 엔티티가 아니면 연관이 아니다.
        //   루트 타입을 모르면 첫 속성 이름으로 추정한다: 같은 이름의 FK 필드가 모두 같은 엔티티를 가리킬 때만.
        for (ServiceInfo svc : services) {
            for (ServiceInfo.AssociationAccess a : svc.getAssociationAccesses()) {
                String owner = a.rootType();
                List<String> path = a.path();
                String how = a.typeSource().name().toLowerCase();
                if (owner != null && owner.startsWith("repo:")) {
                    // 리포지토리 호출 결과 → 그 엔티티. 리포지토리가 아닌 필드의 호출이면 타입을 모르는 것으로 보고 이름 추정.
                    owner = entityOfRepo.get(owner.substring(5));
                }
                if (owner == null) {
                    Set<String> refs = new java.util.TreeSet<>();
                    fkByField.getOrDefault(path.get(0), List.of()).forEach(fk -> refs.add(fk.getReferencedEntity()));
                    if (refs.size() != 1) {
                        resolution.merge(refs.isEmpty() ? "not-association" : "name-ambiguous", 1, Integer::sum);
                        continue;
                    }
                    owner = refs.iterator().next();
                    path = path.subList(1, path.size());
                    how = "name";
                } else if (!known.test(owner)) {
                    resolution.merge("root-not-entity", 1, Integer::sum);
                    continue;
                }
                for (String attr : path) {
                    FkMapping fk = fkByOwnerAndField.get(owner + "#" + attr);
                    if (fk == null) { owner = null; break; }   // 연관이 아닌 getter (예: getTitle)
                    owner = fk.getReferencedEntity();
                }
                if (owner == null || !known.test(owner)) { resolution.merge("not-association", 1, Integer::sum); continue; }
                resolution.merge("linked-" + how, 1, Integer::sum);
                addEdge(methodNode(svc, a.callerMethod(), a.callerLine()), load.apply(owner), RelationType.LOADS);
            }
            // 엔티티 타입 파라미터의 getter: AuthorDto.from(User user) { user.getNickname() } → User 초기화
            for (ServiceInfo.EntityTypeAccess a : svc.getEntityTypeAccesses()) {
                if (!known.test(a.entityType())) continue;
                addEdge(methodNode(svc, a.callerMethod(), a.callerLine()),
                        load.apply(a.entityType()), RelationType.LOADS);
            }
        }
    }

    /** 서비스 메서드는 "svc:", DTO·매퍼 static 메서드는 "helper:" 노드 */
    private Node methodNode(ServiceInfo svc, String method, int line) {
        return getOrCreate(svc.nodePrefix() + svc.getClassName() + "." + method,
                svc.isHelper() ? NodeType.HELPER : NodeType.SERVICE,
                svc.getClassName() + "." + method, svc.getFilePath(), line);
    }

    /** static 호출 대상이 알려진 helper 클래스면 그 노드, 아니면 null (List.of, ResponseEntity.ok 등은 무시) */
    private Node helperNode(String className, String method, int line) {
        if (!helperClasses.contains(className)) return null;
        return getOrCreate("helper:" + className + "." + method, NodeType.HELPER,
                className + "." + method, null, line);
    }

    // ── 4단계: Service → Repository ──────────────────────────────────────────

    private void addServiceEdges(List<ServiceInfo> services) {
        helperClasses.clear();
        for (ServiceInfo svc : services) if (svc.isHelper()) helperClasses.add(svc.getClassName());

        for (ServiceInfo svc : services) {
            // 서비스/DTO → DTO static 메서드 (ReportResponse.from, ReportResponse::from)
            for (ServiceInfo.StaticCall call : svc.getStaticCalls()) {
                Node callee = helperNode(call.calleeClass(), call.calleeMethod(), call.line());
                if (callee != null)
                    addEdge(methodNode(svc, call.callerMethod(), call.callerLine()), callee, RelationType.CALLS);
            }
            for (ServiceInfo.MethodCall call : svc.getMethodCalls()) {
                Node svcNode = methodNode(svc, call.callerMethod, call.callerLine);

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
                // 컨트롤러에서 바로 DTO 로 변환하는 경우 (UserResponse.from(user))
                for (ControllerInfo.ServiceCall call : ep.staticCalls) {
                    Node helper = helperNode(call.serviceClass(), call.serviceMethod(), call.line());
                    if (helper != null) addEdge(ctrlNode, helper, RelationType.CALLS);
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