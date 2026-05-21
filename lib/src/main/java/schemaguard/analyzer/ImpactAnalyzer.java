package schemaguard.analyzer;

import schemaguard.model.*;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.*;

public class ImpactAnalyzer {

    private final DefaultDirectedGraph<Node, Edge> graph;
    private final Map<String, Node> nodeIndex;

    public ImpactAnalyzer(DefaultDirectedGraph<Node, Edge> graph, Map<String, Node> nodeIndex) {
        this.graph     = graph;
        this.nodeIndex = nodeIndex;
    }


    public ImpactResult analyze(List<SchemaChange> changes) {
        List<AffectedApi> result = new ArrayList<>();

        for (SchemaChange change : changes) {
            if (change.getChangeType() == ChangeType.ADD_COLUMN) continue;

            List<Node> startNodes = resolveStartNodes(change);
            for (Node start : startNodes) {
                result.addAll(bfs(start, change));
            }
        }
        return new ImpactResult(deduplicate(result));
    }


    private List<Node> resolveStartNodes(SchemaChange change) {
        return switch (change.getChangeType()) {

            case DROP_COLUMN, RENAME_COLUMN, MODIFY_TYPE, ADD_NOT_NULL, ADD_UNIQUE ->
                    columnNodes(change.getTableName(), change.getColumnName());

            case DROP_TABLE -> {
                List<Node> nodes = new ArrayList<>();
                nodes.addAll(allColumnNodesOfTable(change.getTableName()));
                nodes.addAll(allFkFieldNodesOfTable(change.getTableName()));
                yield nodes;
            }

            case DROP_FK_COLUMN ->
                    columnNodes(change.getTableName(), change.getColumnName());

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
        String prefix = "col:" + table + ".";
        return nodeIndex.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).toList();
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


    private List<AffectedApi> deduplicate(List<AffectedApi> list) {
        Map<String, AffectedApi> seen = new LinkedHashMap<>();
        for (AffectedApi api : list) {
            String key = api.getApiId() + "|" + api.getCause().toString();
            seen.putIfAbsent(key, api);
        }
        return new ArrayList<>(seen.values());
    }
}