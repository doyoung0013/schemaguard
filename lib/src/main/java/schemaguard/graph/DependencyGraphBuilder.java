package schemaguard.graph;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 파싱 결과를 하나의 방향성 의존성 그래프로 통합한다.
 *
 * 엣지 방향: API → Controller → Service → Repository → Field/FkField → Column
 * ImpactAnalyzer는 Column 에서 incomingEdges 로 역방향 BFS 탐색한다.
 *
 * FK 관련 추가 엣지:
 *   fkfield:Post.author  →(fk_maps)→  col:posts.author_id
 *   repo:PostRepository.*  →(uses)→   fkfield:Post.author
 */
public class DependencyGraphBuilder {

    private final DefaultDirectedGraph<Node, Edge> graph =
            new DefaultDirectedGraph<>(Edge.class);
    private final Map<String, Node> nodeIndex = new HashMap<>();

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public DefaultDirectedGraph<Node, Edge> build(
            List<EntityMapping>  entities,
            List<FkMapping>      fkMappings,
            List<RepositoryInfo> repos,
            List<ServiceInfo>    services,
            List<ControllerInfo> controllers) {

        addEntityEdges(entities);
        addFkEdges(fkMappings);                      // ← FK 신규
        addRepositoryEdges(repos, entities, fkMappings); // ← FK 연동
        addServiceEdges(services);
        addControllerEdges(controllers);

        return graph;
    }

    public DefaultDirectedGraph<Node, Edge> getGraph() { return graph; }
    public Map<String, Node> getNodeIndex()            { return nodeIndex; }

    // ── 1단계: 일반 Entity Field → Column ────────────────────────────────────

    private void addEntityEdges(List<EntityMapping> entities) {
        for (EntityMapping em : entities) {
            Node field  = getOrCreate(em.getFieldNodeId(),  NodeType.FIELD,
                    em.getEntityClass() + "." + em.getFieldName());
            Node column = getOrCreate(em.getColumnNodeId(), NodeType.COLUMN,
                    em.getTableName() + "." + em.getColumnName());
            addEdge(field, column, RelationType.MAPS);
        }
    }

    // ── 2단계: FK_FIELD → Column (FK_MAPS 엣지) ───────────────────────────────

    private void addFkEdges(List<FkMapping> fkMappings) {
        for (FkMapping fk : fkMappings) {
            Node fkField = getOrCreate(
                    fk.getFkFieldNodeId(), NodeType.FK_FIELD,
                    fk.getEntityClass() + "." + fk.getFieldName()
                    + " (@" + fk.getFkRelationType() + " → " + fk.getReferencedEntity() + ")");

            Node column = getOrCreate(
                    fk.getColumnNodeId(), NodeType.COLUMN,
                    fk.getTableName() + "." + fk.getJoinColumnName());

            addEdge(fkField, column, RelationType.FK_MAPS);
        }
    }

    // ── 3단계: Repository → Field / FK_FIELD ─────────────────────────────────

    private void addRepositoryEdges(List<RepositoryInfo> repos,
                                    List<EntityMapping>  entities,
                                    List<FkMapping>      fkMappings) {
        for (RepositoryInfo repo : repos) {
            String entityClass = repo.getEntityClass();

            List<EntityMapping> relatedFields = entities.stream()
                    .filter(em -> em.getEntityClass().equals(entityClass)).toList();
            List<FkMapping> relatedFkFields = fkMappings.stream()
                    .filter(fk -> fk.getEntityClass().equals(entityClass)).toList();

            // 선언 메서드 + 기본 CRUD 메서드 모두 처리
            List<String> allMethods = new java.util.ArrayList<>(repo.getMethodNames());
            for (String def : List.of("findById", "findAll", "save", "delete", "deleteById", "existsById")) {
                if (!allMethods.contains(def)) allMethods.add(def);
            }

            for (String methodName : allMethods) {
                String nodeId   = "repo:" + repo.getClassName() + "." + methodName;
                Node repoNode   = getOrCreate(nodeId, NodeType.REPOSITORY,
                        repo.getClassName() + "." + methodName);

                // 일반 필드 연결
                for (EntityMapping em : relatedFields) {
                    Node fieldNode = getOrCreate(em.getFieldNodeId(), NodeType.FIELD,
                            em.getEntityClass() + "." + em.getFieldName());
                    addEdge(repoNode, fieldNode, RelationType.USES);
                }

                // FK 필드 연결 (신규)
                for (FkMapping fk : relatedFkFields) {
                    Node fkFieldNode = getOrCreate(fk.getFkFieldNodeId(), NodeType.FK_FIELD,
                            fk.getEntityClass() + "." + fk.getFieldName());
                    addEdge(repoNode, fkFieldNode, RelationType.USES);
                }
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
                        svc.getClassName() + "." + call.callerMethod);

                NodeType calleeType   = call.calleeClass.contains("Repository")
                        ? NodeType.REPOSITORY : NodeType.SERVICE;
                String   calleePrefix = calleeType == NodeType.REPOSITORY ? "repo:" : "svc:";

                Node calleeNode = getOrCreate(
                        calleePrefix + call.calleeClass + "." + call.calleeMethod,
                        calleeType,
                        call.calleeClass + "." + call.calleeMethod);

                addEdge(svcNode, calleeNode, RelationType.CALLS);
            }
        }
    }

    // ── 5단계: API → Controller → Service ────────────────────────────────────

    private void addControllerEdges(List<ControllerInfo> controllers) {
        for (ControllerInfo ctrl : controllers) {
            for (ControllerInfo.EndpointMapping ep : ctrl.getEndpoints()) {
                Node apiNode  = getOrCreate(ep.apiNodeId(), NodeType.API,
                        ep.httpMethod + " " + ep.path);
                Node ctrlNode = getOrCreate(ep.controllerNodeId(ctrl.getClassName()),
                        NodeType.CONTROLLER,
                        ctrl.getClassName() + "." + ep.controllerMethod);

                addEdge(apiNode, ctrlNode, RelationType.MAPS);

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
        if (!graph.containsEdge(from, to))
            graph.addEdge(from, to, new Edge(relation));
    }
}