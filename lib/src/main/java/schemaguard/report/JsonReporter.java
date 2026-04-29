package schemaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import schemaguard.model.AffectedApi;
import schemaguard.model.ImpactResult;
import schemaguard.model.RiskLevel;

import java.io.File;
import java.util.*;

/**
 * 분석 결과를 JSON 파일로 저장한다. (GitHub Actions CI/CD 연동용)
 *
 * 출력 예시:
 * {
 *   "summary": { "high": 2, "medium": 0, "low": 0, "hasHighRisk": true },
 *   "affectedApis": [
 *     {
 *       "apiId": "GET /users/{id}",
 *       "riskLevel": "HIGH",
 *       "cause": "[HIGH] DROP_COLUMN on users.email",
 *       "impactPath": ["col:users.email", "field:User.email", ...]
 *     }
 *   ]
 * }
 */
public class JsonReporter implements ReportGenerator {

    private final String outputPath;
    private final ObjectMapper mapper;

    public JsonReporter(String outputPath) {
        this.outputPath = outputPath;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void generate(ImpactResult result) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();

            // summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("high",        result.countByRisk(RiskLevel.HIGH));
            summary.put("medium",      result.countByRisk(RiskLevel.MEDIUM));
            summary.put("low",         result.countByRisk(RiskLevel.LOW));
            summary.put("hasHighRisk", result.hasHighRisk());
            root.put("summary", summary);

            // affectedApis
            List<Map<String, Object>> apiList = new ArrayList<>();
            for (AffectedApi api : result.getAffectedApis()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("apiId",       api.getApiId());
                entry.put("riskLevel",   api.getRiskLevel().name());
                entry.put("cause",       api.getCause().toString());
                entry.put("impactPath",  api.getImpactPath());
                apiList.add(entry);
            }
            root.put("affectedApis", apiList);

            mapper.writeValue(new File(outputPath), root);
            System.out.println("JSON report saved to: " + outputPath);

        } catch (Exception e) {
            System.err.println("Failed to write JSON report: " + e.getMessage());
        }
    }
}
