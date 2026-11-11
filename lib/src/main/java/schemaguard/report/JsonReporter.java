package schemaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import schemaguard.model.*;
import schemaguard.parser.MigrationParser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

public class JsonReporter implements ReportGenerator {

    private final String outputPath;
    private final ObjectMapper mapper;
    private final MigrationParser.ParseResult parse;      // null이면 parse 섹션 생략
    private final List<String> sourceParseFailures;

    public JsonReporter(String outputPath) {
        this(outputPath, null, List.of());
    }

    public JsonReporter(String outputPath,
                        MigrationParser.ParseResult parse,
                        List<String> sourceParseFailures) {
        this.outputPath          = outputPath;
        this.mapper              = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.parse               = parse;
        this.sourceParseFailures = sourceParseFailures != null ? sourceParseFailures : List.of();
    }

    /**
     * 리포트를 쓰지 못하면 예외를 던진다(CLI는 종료 코드 2).
     * CI가 리포트 없이 "통과"로 판단하지 않도록 조용히 넘기지 않는다.
     */
    @Override
    public void generate(ImpactResult result) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            boolean parseFailed = parse != null && parse.hasFailures();

            // summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("high",        result.countByRisk(RiskLevel.HIGH));
            summary.put("medium",      result.countByRisk(RiskLevel.MEDIUM));
            summary.put("low",         result.countByRisk(RiskLevel.LOW));
            summary.put("hasHighRisk", result.hasHighRisk());
            summary.put("parseFailures",       parse != null ? parse.failures().size() : 0);
            summary.put("analysisComplete",    !parseFailed);
            summary.put("blocked",             result.hasHighRisk() || parseFailed);
            root.put("summary", summary);

            if (parse != null) {
                root.put("parse", formatParse());
            }

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

            File out = new File(outputPath);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create directory " + parent);
            }
            mapper.writeValue(out, root);
            System.out.println("JSON report saved to: " + outputPath);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JSON report to " + outputPath, e);
        }
    }

    private Map<String, Object> formatParse() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalStatements", parse.totalStatements());
        m.put("failedStatements", parse.failures().size());

        List<Map<String, Object>> failures = new ArrayList<>();
        for (MigrationParser.ParseFailure f : parse.failures()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("line",      f.line());
            fm.put("statement", f.statement());
            fm.put("reason",    f.reason());
            failures.add(fm);
        }
        m.put("failures", failures);

        List<String> detected = new ArrayList<>();
        for (SchemaChange c : parse.changes()) {
            detected.add(c.toString());
        }
        m.put("detectedChanges", detected);

        // Java 소스 파싱 실패는 차단하지 않고 경고로만 남긴다 (해당 파일의 영향 경로가 누락될 수 있음).
        m.put("sourceParseFailures", sourceParseFailures);
        return m;
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