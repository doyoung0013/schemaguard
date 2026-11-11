package schemaguard.analyzer;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.*;

public class ImpactAnalyzer {

    private final DefaultDirectedGraph<Node, Edge> graph;
    private final Map<String, Node> nodeIndex;
    private final Map<String, List<Node>> fkFieldsReferencingTable;

    /**
     * 테이블명 → 그 테이블의 컬럼 노드. 처음 필요할 때 nodeIndex를 한 번만 훑어 만든다.
     * 예전에는 테이블 단위 변경마다 nodeIndex 전체를 "col:테이블." 접두사로 두 번씩 훑었다
     * (변경 수 × 전체 노드 수).
     */
    private Map<String, List<Node>> columnsByTable;

    public ImpactAnalyzer(DefaultDirectedGraph<Node, Edge> graph, Map<String, Node> nodeIndex) {
        this(graph, nodeIndex, Map.of());
    }

    public ImpactAnalyzer(DefaultDirectedGraph<Node, Edge> graph, Map<String, Node> nodeIndex,
                          Map<String, List<Node>> fkFieldsReferencingTable) {
        this.graph     = graph;
        this.nodeIndex = nodeIndex;
        this.fkFieldsReferencingTable = fkFieldsReferencingTable;
    }


    public ImpactResult analyze(List<SchemaChange> changes) {
        List<AffectedApi> result = new ArrayList<>();

        for (SchemaChange change : changes) {
            if (change.getChangeType() == ChangeType.ADD_COLUMN) continue;

            for (Node start : sortedDistinct(resolveStartNodes(change))) {
                result.addAll(bfs(start, change));
            }
        }
        return new ImpactResult(deduplicate(result));
    }


    private List<Node> resolveStartNodes(SchemaChange change) {
        return switch (change.getChangeType()) {

            case DROP_COLUMN, RENAME_COLUMN, DROP_FK_COLUMN, MODIFY_TYPE, ADD_NOT_NULL, ADD_UNIQUE ->
                    columnNodes(change.getTableName(), change.getColumnName());

            // 테이블명 변경은 @Table 매핑이 그대로면 테이블 삭제와 같은 범위가 깨진다.
            case DROP_TABLE, RENAME_TABLE -> {
                List<Node> nodes = new ArrayList<>();
                nodes.addAll(allColumnNodesOfTable(change.getTableName()));
                nodes.addAll(allFkFieldNodesOfTable(change.getTableName()));
                // 이 테이블을 FK로 참조하는 다른 엔티티(예: PostTag.tag → tags)도 깨진다.
                nodes.addAll(fkFieldsReferencingTable.getOrDefault(change.getTableName(), List.of()));
                yield nodes;
            }

            case DROP_FOREIGN_KEY ->
                    allFkFieldNodesOfTable(change.getTableName());

            case MODIFY_FK_REFERENCE -> {
                List<Node> nodes = new ArrayList<>();
                nodes.addAll(allFkFieldNodesOfTable(change.getTableName()));
                if (change.getReferencedTable() != null)
                    nodes.addAll(allColumnNodesOfTable(change.getReferencedTable()));
                yield nodes;
            }

            case ADD_FOREIGN_KEY ->
                    columnNodes(change.getTableName(), change.getColumnName());

            default -> List.of();
        };
    }


    private List<AffectedApi> bfs(Node startNode, SchemaChange change) {
        List<AffectedApi> result = new ArrayList<>();
        Map<Node, Node>   parent  = new LinkedHashMap<>();
        Queue<Node>       queue   = new LinkedList<>();
        Set<Node>         visited = new HashSet<>();

        queue.add(startNode);
        visited.add(startNode);
        parent.put(startNode, null);

        while (!queue.isEmpty()) {
            Node current = queue.poll();

            if (current.getType() == NodeType.API) {
                // Node 객체 그대로 경로 재구성 (위치 정보 보존)
                List<Node> pathNodes = reconstructPathNodes(parent, current);
                result.add(new AffectedApi(
                        current.getName(),
                        change.getRiskLevel(),
                        change,
                        pathNodes));
                continue;
            }

            // 엣지 삽입 순서가 아니라 노드 id 순으로 방문해야 같은 거리의 경로 중 항상 같은 것이 남는다.
            List<Node> neighbors = new ArrayList<>();
            for (Edge edge : graph.incomingEdgesOf(current)) neighbors.add(graph.getEdgeSource(edge));
            if (!followsAssociationLoads(change)) {
                neighbors.removeIf(n -> n.getType() == NodeType.ENTITY_LOAD
                        || graph.getEdge(n, current).getRelationType() == RelationType.LOADS);
            }
            neighbors.sort(BY_ID);
            for (Node neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        return result;
    }


    /** parent 맵을 역추적하여 시작 노드 → API 순서의 Node 리스트를 반환 */
    private List<Node> reconstructPathNodes(Map<Node, Node> parent, Node end) {
        LinkedList<Node> path = new LinkedList<>();
        Node cursor = end;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = parent.get(cursor);
        }
        return path;
    }


    private List<Node> columnNodes(String table, String column) {
        if (column == null) return List.of();
        Node node = nodeIndex.get("col:" + table + "." + column);
        return node != null ? List.of(node) : List.of();
    }

    private List<Node> allColumnNodesOfTable(String table) {
        if (columnsByTable == null) {
            Map<String, List<Node>> index = new HashMap<>();
            for (Map.Entry<String, Node> e : nodeIndex.entrySet()) {
                String id = e.getKey();
                if (!id.startsWith("col:")) continue;
                int dot = id.lastIndexOf('.');      // "col:<table>.<column>", 컬럼명에는 '.'이 없다
                if (dot <= 4) continue;
                index.computeIfAbsent(id.substring(4, dot), t -> new ArrayList<>()).add(e.getValue());
            }
            columnsByTable = index;
        }
        return columnsByTable.getOrDefault(table, List.of());
    }

    private List<Node> allFkFieldNodesOfTable(String table) {
        List<Node> colNodes = allColumnNodesOfTable(table);
        List<Node> result   = new ArrayList<>();
        for (Node col : colNodes) {
            for (Edge edge : graph.incomingEdgesOf(col)) {
                if (edge.getRelationType() == RelationType.FK_MAPS)
                    result.add(graph.getEdgeSource(edge));
            }
        }
        return result;
    }


    /**
     * 연관 로딩 경로(LOADS)는 "읽기가 깨지는" 변경에서만 따라간다.
     * 컬럼/테이블이 사라지거나 이름이 바뀌면 연관 엔티티를 SELECT 하는 순간 실패한다.
     * 타입 변경, NOT NULL, UNIQUE 는 기존 값을 읽는 데는 영향이 없거나 데이터에 따라 달라서 제외한다
     * (실제 MySQL 측정에서 이 변경들의 오탐이 가장 많았다).
     */
    private static boolean followsAssociationLoads(SchemaChange change) {
        return switch (change.getChangeType()) {
            case DROP_COLUMN, RENAME_COLUMN, DROP_FK_COLUMN, DROP_TABLE, RENAME_TABLE -> true;
            default -> false;
        };
    }

    private static final Comparator<Node> BY_ID = Comparator.comparing(Node::getId);

    /** 시작 노드를 중복 제거하고 id 순으로 정렬한다 (nodeIndex/엣지 순회 순서에 의존하지 않도록). */
    private static List<Node> sortedDistinct(List<Node> nodes) {
        Map<String, Node> byId = new TreeMap<>();
        for (Node n : nodes) byId.putIfAbsent(n.getId(), n);
        return new ArrayList<>(byId.values());
    }

    /** 경로 비교: 짧은 경로 우선, 길이가 같으면 노드 id 사전순. */
    private static final Comparator<AffectedApi> BETTER_PATH =
            Comparator.<AffectedApi>comparingInt(a -> a.getImpactNodes().size())
                    .thenComparing(a -> String.join(">", a.getImpactPath()));

    /** 리포트 순서: 위험도(HIGH 먼저) → API → 원인. */
    private static final Comparator<AffectedApi> REPORT_ORDER =
            Comparator.<AffectedApi, RiskLevel>comparing(AffectedApi::getRiskLevel)
                    .thenComparing(AffectedApi::getApiId)
                    .thenComparing(a -> a.getCause().toString());

    /**
     * 같은 (API, 원인) 쌍이 여러 시작 노드에서 발견되면 하나만 남긴다.
     * "처음 들어온 것"이 아니라 정해진 규칙(BETTER_PATH)으로 골라서 입력 순서와 무관하게 만든다.
     */
    private List<AffectedApi> deduplicate(List<AffectedApi> list) {
        Map<String, AffectedApi> best = new HashMap<>();
        for (AffectedApi api : list) {
            String key = api.getApiId() + "|" + api.getCause().toString();
            best.merge(key, api, (a, b) -> BETTER_PATH.compare(a, b) <= 0 ? a : b);
        }
        List<AffectedApi> result = new ArrayList<>(best.values());
        result.sort(REPORT_ORDER);
        return result;
    }
}