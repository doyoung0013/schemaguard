package schemaguard.model;

import java.util.List;

/**
 * 스키마 변경으로 영향을 받는 단일 API 엔드포인트 정보.
 */
public class AffectedApi {

    private final String apiId;          // ex. "GET /users/{id}"
    private final RiskLevel riskLevel;
    private final SchemaChange cause;    // 원인이 된 스키마 변경
    private final List<String> impactPath; // ex. ["col:users.email", "field:User.email", ..., "api:GET /users/{id}"]

    public AffectedApi(String apiId, RiskLevel riskLevel,
                       SchemaChange cause, List<String> impactPath) {
        this.apiId      = apiId;
        this.riskLevel  = riskLevel;
        this.cause      = cause;
        this.impactPath = impactPath;
    }

    public String getApiId()             { return apiId; }
    public RiskLevel getRiskLevel()      { return riskLevel; }
    public SchemaChange getCause()       { return cause; }
    public List<String> getImpactPath()  { return impactPath; }
}
