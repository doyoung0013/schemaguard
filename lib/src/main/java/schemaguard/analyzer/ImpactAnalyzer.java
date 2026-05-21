package schemaguard.analyzer;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.*;


/**
 * 변경된 DB 컬럼/FK를 시작점으로 의존성 그래프를 역방향 BFS 탐색하여
 * 영향받는 API 엔드포인트와 경로를 도출한다.
 *
 * ── FK 변경 유형별 시작 노드 ──────────────────────────────────────────────────
 *
 * DROP_FOREIGN_KEY
 *   → FK 이름으로 직접 시작 노드를 특정할 수 없으므로,
 *     해당 테이블의 모든 fkfield:* 노드를 시작점으로 탐색
 *
 * DROP_FK_COLUMN
 *   → col:tableName.columnName 노드에서 시작
 *     (일반 DROP_COLUMN 과 동일 로직, 단 FK_MAPS 엣지도 역추적)
 *
 * MODIFY_FK_REFERENCE
 *   → 참조 테이블이 바뀌는 것이므로 해당 테이블의 fkfield:* 노드 전체를 시작점으로
 *
 * ADD_FOREIGN_KEY
 *   → col:tableName.columnName 노드에서 시작 (제약 위반 가능 경로 추적)
 */
public class ImpactAnalyzer {

    private final DefaultDirectedGraph<Node, Edge> graph;
    private final Map<String, Node> nodeIndex;

    public ImpactAnalyzer(DefaultDirectedGraph<Node, Edge> graph, Map<String, Node> nodeIndex) {
        this.graph     = graph;
        this.nodeIndex = nodeIndex;
    }

    // ── 진입점 ────────────────────────────────────────────────────────────────

    public ImpactResult analyze(List<SchemaChange> changes) {
        List<AffectedApi> result = new ArrayList<>();

        for (SchemaChange change : changes) {
            if (change.getChangeType() == ChangeType.ADD_COLUMN) continue; // 영향 없음

            List<Node> startNodes = resolveStartNodes(change);
            for (Node start : startNodes) {
                result.addAll(bfs(start, change));
            }
        }

        return new ImpactResult(deduplicate(result));
    }

    // ── 시작 노드 결정 ────────────────────────────────────────────────────────

    private List<Node> resolveStartNodes(SchemaChange change) {
        return switch (change.getChangeType()) {

            // ── 일반 컬럼 변경 ──────────────────────────────────────────────
            case DROP_COLUMN, RENAME_COLUMN, MODIFY_TYPE,
                 ADD_NOT_NULL, ADD_UNIQUE -> {
                yield columnNodes(change.getTableName(), change.getColumnName());
            }

            // ── 테이블 삭제 ────────────────────────────────────────────────
            case DROP_TABLE -> {
                // 해당 테이블의 모든 컬럼 노드 + 모든 FK 필드 노드
                List<Node> nodes = new ArrayList<>();
                nodes.addAll(allColumnNodesOfTable(change.getTableName()));
                nodes.addAll(allFkFieldNodesOfTable(change.getTableName()));
                yield nodes;
            }

            // ── FK 컬럼 삭제: col 노드에서 시작 (FK_MAPS 엣지로 fkfield 도달) ──
            case DROP_FK_COLUMN -> {
                yield columnNodes(change.getTableName(), change.getColumnName());
            }

            // ── FK 제약 삭제: fkfield 노드 전체를 시작점으로 ─────────────────
            case DROP_FOREIGN_KEY -> {
                yield allFkFieldNodesOfTable(change.getTableName());
            }

            // ── FK 참조 변경: fkfield 노드 전체를 시작점으로 ─────────────────
            case MODIFY_FK_REFERENCE -> {
                List<Node> nodes = new ArrayList<>();
                nodes.addAll(allFkFieldNodesOfTable(change.getTableName()));
                // 참조 대상 테이블의 컬럼 노드도 추가 (참조 무결성 영향)
                if (change.getReferencedTable() != null) {
                    nodes.addAll(allColumnNodesOfTable(change.getReferencedTable()));
                }
                yield nodes;
            }

            // ── FK 제약 추가: col 노드에서 시작 (INSERT 로직 제약 위반 추적) ──
            case ADD_FOREIGN_KEY -> {
                yield columnNodes(change.getTableName(), change.getColumnName());
            }

            default -> List.of();
        };
    }

    // ── BFS 역방향 탐색 ───────────────────────────────────────────────────────

    private List<AffectedApi> bfs(Node startNode, SchemaChange change) {
        List<AffectedApi> result = new ArrayList<>();
        Map<Node, Node>   parent = new LinkedHashMap<>();
        Queue<Node>       queue  = new LinkedList<>();
        Set<Node>         visited = new HashSet<>();

        queue.add(startNode);
        visited.add(startNode);
        parent.put(startNode, null);

        while (!queue.isEmpty()) {
            Node current = queue.poll();

            if (current.getType() == NodeType.API) {
                result.add(new AffectedApi(
                        current.getName(),
                        change.getRiskLevel(),
                        change,
                        reconstructPath(parent, current)));
                continue;
            }

            for (Edge edge : graph.incomingEdgesOf(current)) {
                Node neighbor = graph.getEdgeSource(edge);
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }

    // ── 경로 재구성 ───────────────────────────────────────────────────────────

    private List<String> reconstructPath(Map<Node, Node> parent, Node end) {
        LinkedList<String> path = new LinkedList<>();
        Node cursor = end;
        while (cursor != null) {
            path.addFirst(cursor.getId());
            cursor = parent.get(cursor);
        }
        return path;
    }

    // ── 노드 검색 헬퍼 ────────────────────────────────────────────────────────

    private List<Node> columnNodes(String table, String column) {
        if (column == null) return List.of();
        String id = "col:" + table + "." + column;
        Node node = nodeIndex.get(id);
        return node != null ? List.of(node) : List.of();
    }

    private List<Node> allColumnNodesOfTable(String table) {
        String prefix = "col:" + table + ".";
        return nodeIndex.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** 특정 테이블을 소유하는 Entity 의 fkfield 노드 전체 반환 */
    private List<Node> allFkFieldNodesOfTable(String table) {
        // fkfield 노드의 ID 는 "fkfield:EntityClass.fieldName"
        // 해당 테이블과 연결된 col 노드로부터 역방향 FK_MAPS 엣지를 따라가 fkfield 를 수집
        List<Node> colNodes = allColumnNodesOfTable(table);
        List<Node> result   = new ArrayList<>();

        for (Node col : colNodes) {
            for (Edge edge : graph.incomingEdgesOf(col)) {
                if (edge.getRelationType() == RelationType.FK_MAPS) {
                    result.add(graph.getEdgeSource(edge));
                }
            }
        }
        return result;
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
