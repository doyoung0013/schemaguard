package schemaguard.graph;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        for (RepositoryInfo repo : repos) {
            String entityClass = repo.getEntityClass();

            List<EntityMapping> relatedFields = entities.stream()
                    .filter(em -> em.getEntityClass().equals(entityClass)).toList();
            List<FkMapping> relatedFkFields = fkMappings.stream()
                    .filter(fk -> fk.getEntityClass().equals(entityClass)).toList();

            // 선언 메서드 + 기본 CRUD
            List<RepositoryInfo.MethodEntry> allMethods =
                    new java.util.ArrayList<>(repo.getMethods());
            for (String def : List.of("findById","findAll","save","delete","deleteById","existsById")) {
                if (allMethods.stream().noneMatch(e -> e.name().equals(def)))
                    allMethods.add(new RepositoryInfo.MethodEntry(def, repo.getClassLine()));
            }

            for (RepositoryInfo.MethodEntry entry : allMethods) {
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

    private Node getOrCreate(String id, NodeType type, String name,
                              String rawFilePath, int lineNumber) {
        return nodeIndex.computeIfAbsent(id, k -> {
            String relativePath = toRelativePath(rawFilePath);
            Node n = new Node(id, type, name, relativePath, lineNumber);
            graph.addVertex(n);
            return n;
        });
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