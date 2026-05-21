package schemaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import schemaguard.model.*;

import java.io.File;
import java.util.*;

public class JsonReporter implements ReportGenerator {

    private final String outputPath;
    private final ObjectMapper mapper;

    public JsonReporter(String outputPath) {
        this.outputPath = outputPath;
        this.mapper     = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
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
                entry.put("apiId",      api.getApiId());
                entry.put("riskLevel",  api.getRiskLevel().name());
                entry.put("cause",      formatCause(api.getCause()));
                entry.put("isFkChange", api.getCause().isFkChange());

                // 위치 정보 포함 경로
                List<Map<String, Object>> pathList = new ArrayList<>();
                for (Node node : api.getImpactNodes()) {
                    Map<String, Object> nodeMap = new LinkedHashMap<>();
                    nodeMap.put("id",       node.getId());
                    nodeMap.put("type",     node.getType().name());
                    nodeMap.put("name",     node.getName());
                    nodeMap.put("filePath", node.getFilePath());
                    nodeMap.put("line",     node.getLineNumber() > 0 ? node.getLineNumber() : null);
                    pathList.add(nodeMap);
                }
                entry.put("impactPath", pathList);
                apiList.add(entry);
            }
            root.put("affectedApis", apiList);

            mapper.writeValue(new File(outputPath), root);
            System.out.println("JSON report saved to: " + outputPath);

        } catch (Exception e) {
            System.err.println("Failed to write JSON report: " + e.getMessage());
        }
    }

    private Map<String, Object> formatCause(SchemaChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",             c.getChangeType().name());
        m.put("table",            c.getTableName());
        m.put("column",           c.getColumnName());
        m.put("fkName",           c.getFkName());
        m.put("referencedTable",  c.getReferencedTable());
        m.put("referencedColumn", c.getReferencedColumn());
        return m;
    }
}