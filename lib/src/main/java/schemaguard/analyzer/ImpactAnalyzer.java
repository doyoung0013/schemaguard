package schemaguard.analyzer;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.*;

/**
 * 변경된 DB 컬럼을 시작점으로 의존성 그래프를 역방향 BFS 탐색하여
 * 영향받는 API 엔드포인트와 영향 경로를 도출한다.
 *
 * 탐색 방향:
 *   col:users.email
 *     ← (역방향 incomingEdges) ← field:User.email
 *     ← repo:UserRepository.findById
 *     ← svc:UserService.getUser
 *     ← ctrl:UserController.getUser
 *     ← api:GET /users/{id}   ← 여기서 API 노드 발견 → AffectedApi 등록
 */
public class ImpactAnalyzer {

    private final DefaultDirectedGraph<Node, Edge> graph;
    private final Map<String, Node> nodeIndex;

    public ImpactAnalyzer(DefaultDirectedGraph<Node, Edge> graph, Map<String, Node> nodeIndex) {
        this.graph     = graph;
        this.nodeIndex = nodeIndex;
    }

    public ImpactResult analyze(List<SchemaChange> changes) {
        List<AffectedApi> affectedApis = new ArrayList<>();

        for (SchemaChange change : changes) {
            // 변경 유형이 ADD_COLUMN(LOW)이면 기존 코드에 영향 없음 → 스킵
            if (change.getChangeType() == ChangeType.ADD_COLUMN) continue;

            List<Node> startNodes = findStartNodes(change);
            for (Node startNode : startNodes) {
                affectedApis.addAll(bfsFromNode(startNode, change));
            }
        }

        // 중복 제거 (같은 API + 같은 원인 변경)
        return new ImpactResult(deduplicate(affectedApis));
    }

    // ── 시작 노드 결정 ────────────────────────────────────────────────────────

    /**
     * SchemaChange 에 대응하는 그래프 노드를 찾는다.
     *
     * DROP_TABLE / DROP_COLUMN → col:tableName.columnName
     * RENAME_COLUMN            → 이전 컬럼 노드
     */
    private List<Node> findStartNodes(SchemaChange change) {
        List<Node> nodes = new ArrayList<>();

        if (change.getChangeType() == ChangeType.DROP_TABLE) {
            // 테이블의 모든 컬럼 노드를 시작점으로
            String prefix = "col:" + change.getTableName() + ".";
            nodeIndex.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .forEach(nodes::add);
        } else if (change.getColumnName() != null) {
            String colId = "col:" + change.getTableName() + "." + change.getColumnName();
            Node colNode = nodeIndex.get(colId);
            if (colNode != null) nodes.add(colNode);
        }

        return nodes;
    }

    // ── BFS 역방향 탐색 ───────────────────────────────────────────────────────

    private List<AffectedApi> bfsFromNode(Node startNode, SchemaChange change) {
        List<AffectedApi> result = new ArrayList<>();

        // 방문 노드와 경로를 함께 추적
        Map<Node, Node> parentMap = new LinkedHashMap<>(); // 경로 역추적용
        Queue<Node> queue = new LinkedList<>();
        Set<Node> visited = new HashSet<>();

        queue.add(startNode);
        visited.add(startNode);
        parentMap.put(startNode, null);

        while (!queue.isEmpty()) {
            Node current = queue.poll();

            if (current.getType() == NodeType.API) {
                // API 노드 발견 → 경로 재구성
                List<String> path = reconstructPath(parentMap, startNode, current);
                result.add(new AffectedApi(
                        current.getName(),
                        change.getRiskLevel(),
                        change,
                        path));
                continue; // API에서 더 위로 올라갈 필요 없음
            }

            // 역방향 탐색: 이 노드로 들어오는 엣지의 source 를 큐에 추가
            for (Edge edge : graph.incomingEdgesOf(current)) {
                Node neighbor = graph.getEdgeSource(edge);
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parentMap.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        return result;
    }

    // ── 경로 재구성 ───────────────────────────────────────────────────────────

    /**
     * parentMap을 역추적하여 startNode → apiNode 의 경로를 문자열 리스트로 반환한다.
     */
    private List<String> reconstructPath(Map<Node, Node> parentMap, Node start, Node end) {
        LinkedList<String> path = new LinkedList<>();
        Node cursor = end;
        while (cursor != null) {
            path.addFirst(cursor.getId());
            cursor = parentMap.get(cursor);
        }
        return path;
    }

    // ── 중복 제거 ─────────────────────────────────────────────────────────────

    private List<AffectedApi> deduplicate(List<AffectedApi> list) {
        Map<String, AffectedApi> seen = new LinkedHashMap<>();
        for (AffectedApi api : list) {
            String key = api.getApiId() + "|" + api.getCause().toString();
            seen.putIfAbsent(key, api);
        }
        return new ArrayList<>(seen.values());
    }
}
