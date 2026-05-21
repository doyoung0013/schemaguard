package schemaguard.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 스키마 변경으로 영향을 받는 단일 API 엔드포인트 정보.
 *
 * impactNodes: 영향 경로의 Node 객체 리스트 (위치 정보 포함)
 *   ex. [col:users.email, field:User.email, repo:UserRepository.findById, ...]
 */
public class AffectedApi {

    private final String      apiId;
    private final RiskLevel   riskLevel;
    private final SchemaChange cause;
    private final List<Node>  impactNodes;   // Node 객체로 위치 정보 보존

    public AffectedApi(String apiId, RiskLevel riskLevel,
                       SchemaChange cause, List<Node> impactNodes) {
        this.apiId       = apiId;
        this.riskLevel   = riskLevel;
        this.cause       = cause;
        this.impactNodes = impactNodes;
    }

    public String       getApiId()       { return apiId; }
    public RiskLevel    getRiskLevel()   { return riskLevel; }
    public SchemaChange getCause()       { return cause; }
    public List<Node>   getImpactNodes() { return impactNodes; }

    /** 기존 ConsoleReporter 호환용: Node ID 문자열 리스트 반환 */
    public List<String> getImpactPath() {
        return impactNodes.stream().map(Node::getId).collect(Collectors.toList());
    }
}