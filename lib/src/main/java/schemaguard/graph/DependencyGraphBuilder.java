package schemaguard.graph;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 파싱 결과를 하나의 방향성 의존성 그래프로 통합한다.
 *
 * 그래프 방향 (엣지 방향 = "영향이 전파되는 방향의 역방향"):
 *   col:users.email  ←(maps)←  field:User.email
 *   field:User.email ←(uses)←  repo:UserRepository.findById
 *   repo:...         ←(calls)← svc:UserService.getUser
 *   svc:...          ←(calls)← ctrl:UserController.getUser
 *   ctrl:...         ←(maps)←  api:GET /users/{id}
 *
 * 즉, 엣지는 "API → ... → Column" 방향으로 추가된다.
 * ImpactAnalyzer는 Column 에서 출발하여 이 방향의 역방향(incomingEdges)으로 BFS 탐색한다.
 */
public class DependencyGraphBuilder {

    private final DefaultDirectedGraph<Node, Edge> graph =
            new DefaultDirectedGraph<>(Edge.class);

    /** 노드 id → Node 역조회용 */
    private final Map<String, Node> nodeIndex = new HashMap<>();

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public DefaultDirectedGraph<Node, Edge> build(
            List<EntityMapping>  entities,
            List<RepositoryInfo> repos,
            List<ServiceInfo>    services,
            List<ControllerInfo> controllers) {

        addEntityEdges(entities);
        addRepositoryEdges(repos, entities);
        addServiceEdges(services);
        addControllerEdges(controllers);

        return graph;
    }

    public DefaultDirectedGraph<Node, Edge> getGraph() { return graph; }
    public Map<String, Node> getNodeIndex()            { return nodeIndex; }

    // ── 1단계: Entity → Column 엣지 ──────────────────────────────────────────

    private void addEntityEdges(List<EntityMapping> entities) {
        for (EntityMapping em : entities) {
            Node fieldNode  = getOrCreate(em.getFieldNodeId(),  NodeType.FIELD,
                    em.getEntityClass() + "." + em.getFieldName());
            Node columnNode = getOrCreate(em.getColumnNodeId(), NodeType.COLUMN,
                    em.getTableName() + "." + em.getColumnName());

            // api → col 방향이므로 field → column
            addEdge(fieldNode, columnNode, RelationType.MAPS);
        }
    }

    // ── 2단계: Repository → Field 엣지 ───────────────────────────────────────

    private void addRepositoryEdges(List<RepositoryInfo> repos, List<EntityMapping> entities) {
        for (RepositoryInfo repo : repos) {
            String entityClass = repo.getEntityClass();

            // 이 Repository 가 다루는 Entity 의 모든 필드를 찾아 연결
            List<EntityMapping> relatedFields = entities.stream()
                    .filter(em -> em.getEntityClass().equals(entityClass))
                    .toList();

            for (String methodName : repo.getMethodNames()) {
                Node repoNode = getOrCreate(
                        "repo:" + repo.getClassName() + "." + methodName,
                        NodeType.REPOSITORY,
                        repo.getClassName() + "." + methodName);

                for (EntityMapping em : relatedFields) {
                    Node fieldNode = getOrCreate(em.getFieldNodeId(), NodeType.FIELD,
                            em.getEntityClass() + "." + em.getFieldName());
                    addEdge(repoNode, fieldNode, RelationType.USES);
                }
            }

            // 상속된 기본 CRUD 메서드도 노드로 추가 (findById, save, delete 등)
            for (String defaultMethod : List.of("findById", "findAll", "save", "delete", "deleteById", "existsById")) {
                String nodeId = "repo:" + repo.getClassName() + "." + defaultMethod;
                if (!nodeIndex.containsKey(nodeId)) {
                    Node repoNode = getOrCreate(nodeId, NodeType.REPOSITORY,
                            repo.getClassName() + "." + defaultMethod);
                    for (EntityMapping em : relatedFields) {
                        Node fieldNode = getOrCreate(em.getFieldNodeId(), NodeType.FIELD,
                                em.getEntityClass() + "." + em.getFieldName());
                        addEdge(repoNode, fieldNode, RelationType.USES);
                    }
                }
            }
        }
    }

    // ── 3단계: Service → Repository 엣지 ─────────────────────────────────────

    private void addServiceEdges(List<ServiceInfo> services) {
        for (ServiceInfo svc : services) {
            for (ServiceInfo.MethodCall call : svc.getMethodCalls()) {
                Node svcNode  = getOrCreate(
                        "svc:" + svc.getClassName() + "." + call.callerMethod,
                        NodeType.SERVICE,
                        svc.getClassName() + "." + call.callerMethod);

                // callee 가 Repository 인지 Service 인지 판단
                NodeType calleeType = call.calleeClass.contains("Repository")
                        ? NodeType.REPOSITORY : NodeType.SERVICE;
                String calleePrefix = calleeType == NodeType.REPOSITORY ? "repo:" : "svc:";

                Node calleeNode = getOrCreate(
                        calleePrefix + call.calleeClass + "." + call.calleeMethod,
                        calleeType,
                        call.calleeClass + "." + call.calleeMethod);

                addEdge(svcNode, calleeNode, RelationType.CALLS);
            }
        }
    }

    // ── 4단계: API → Controller → Service 엣지 ───────────────────────────────

    private void addControllerEdges(List<ControllerInfo> controllers) {
        for (ControllerInfo ctrl : controllers) {
            for (ControllerInfo.EndpointMapping ep : ctrl.getEndpoints()) {
                Node apiNode  = getOrCreate(ep.apiNodeId(), NodeType.API,
                        ep.httpMethod + " " + ep.path);
                Node ctrlNode = getOrCreate(ep.controllerNodeId(ctrl.getClassName()),
                        NodeType.CONTROLLER,
                        ctrl.getClassName() + "." + ep.controllerMethod);

                // API → Controller
                addEdge(apiNode, ctrlNode, RelationType.MAPS);

                // Controller → Service (호출이 파악된 경우만)
                if (ep.calleeServiceClass != null && ep.calleeServiceMethod != null) {
                    Node svcNode = getOrCreate(
                            "svc:" + ep.calleeServiceClass + "." + ep.calleeServiceMethod,
                            NodeType.SERVICE,
                            ep.calleeServiceClass + "." + ep.calleeServiceMethod);
                    addEdge(ctrlNode, svcNode, RelationType.CALLS);
                }
            }
        }
    }

    // ── 그래프 유틸 ──────────────────────────────────────────────────────────

    private Node getOrCreate(String id, NodeType type, String name) {
        return nodeIndex.computeIfAbsent(id, k -> {
            Node n = new Node(id, type, name);
            graph.addVertex(n);
            return n;
        });
    }

    private void addEdge(Node from, Node to, RelationType relation) {
        if (!graph.containsEdge(from, to)) {
            graph.addEdge(from, to, new Edge(relation));
        }
    }
}
