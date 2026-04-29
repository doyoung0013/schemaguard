package schemaguard.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * 의존성 그래프의 엣지.
 * JGraphT DefaultEdge를 상속하여 RelationType 정보를 추가한다.
 */
public class Edge extends DefaultEdge {
 
    private final RelationType relationType;
 
    public Edge(RelationType relationType) {
        this.relationType = relationType;
    }
 
    public RelationType getRelationType() { return relationType; }
 
    @Override
    public String toString() { return relationType.name().toLowerCase(); }
}
 
