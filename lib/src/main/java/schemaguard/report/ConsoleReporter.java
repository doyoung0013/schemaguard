package schemaguard.report;

import schemaguard.model.*;

import java.util.*;

public class ConsoleReporter implements ReportGenerator {

    private static final String RESET  = "\u001B[0m";
    private static final String RED    = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN  = "\u001B[32m";
    private static final String CYAN   = "\u001B[36m";
    private static final String BOLD   = "\u001B[1m";

    //private final boolean en = "en".equalsIgnoreCase(System.getProperty("schemaguard.lang", "ko"));
    private final boolean en =
            "en".equalsIgnoreCase(System.getProperty("schemaguard.lang"))
            || "en".equalsIgnoreCase(System.getenv("SCHEMAGUARD_LANG"));
    
    
    private String msg(String ko, String en) {
        return this.en ? en : ko;
    }

    @Override
    public void generate(ImpactResult result) {
        printHeader();
        printSummary(result);

        if (result.isEmpty()) {
            System.out.println(GREEN + msg("  영향을 받는 API가 없습니다.", "  No affected APIs") + RESET);
            printFooter(result);
            return;
        }

        printSection("HIGH RISK", result.getByRisk(RiskLevel.HIGH), RED);
        printSection("MEDIUM RISK", result.getByRisk(RiskLevel.MEDIUM), YELLOW);
        printSection("LOW RISK", result.getByRisk(RiskLevel.LOW), GREEN);

        printFooter(result);
    }

    private void printHeader() {
        System.out.println();
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println(BOLD + "      SchemaGuard Report                " + RESET);
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println();
    }

    private void printSummary(ImpactResult result) {
        int total = result.getAffectedApis().size();
        RiskLevel overallRisk = getOverallRisk(result);

        System.out.println(BOLD + msg("분석 요약", "Summary") + RESET);
        System.out.println(msg("  영향 API 수 : ", "  Total APIs   : ") + total);
        System.out.println(msg("  전체 위험도 : ", "  Overall Risk : ") + overallRisk);
        System.out.println();

        if (!result.isEmpty()) {
            printCauseRanking(result);
            printCauseGuideSummary(result);
            printCoreImpactNodes(result);
        }
    }

    private void printCauseRanking(ImpactResult result) {
        Map<String, Integer> causeCountMap = new LinkedHashMap<>();

        for (AffectedApi api : result.getAffectedApis()) {
            String cause = api.getCause().toString();
            causeCountMap.put(cause, causeCountMap.getOrDefault(cause, 0) + 1);
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(causeCountMap.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        System.out.println(BOLD + msg("영향 원인 순위", "Cause Ranking") + RESET);

        int rank = 1;
        for (Map.Entry<String, Integer> entry : entries) {
            System.out.println("  [" + rank + "] " + entry.getKey()
                    + msg(" -> 영향 API ", " -> APIs : ")
                    + entry.getValue());
            rank++;
        }

        System.out.println();
    }

    private void printCoreImpactNodes(ImpactResult result) {
        Map<String, Integer> nodeCountMap = new LinkedHashMap<>();

        for (AffectedApi api : result.getAffectedApis()) {
            for (String path : api.getImpactPath()) {
                if (isCoreNode(path)) {
                    String node = removePrefix(path);
                    nodeCountMap.put(node, nodeCountMap.getOrDefault(node, 0) + 1);
                }
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(nodeCountMap.entrySet());
        entries.removeIf(entry -> entry.getValue() < 2);
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        if (entries.isEmpty()) return;

        System.out.println(BOLD + msg("핵심 영향 노드", "Core Nodes") + RESET);

        int limit = Math.min(entries.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            System.out.println("  - " + entry.getKey()
                    + msg(" -> ", " -> ")
                    + entry.getValue()
                    + msg("개 API에 영향", " APIs"));
        }

        System.out.println();
    }

    private boolean isCoreNode(String path) {
        return path.startsWith("field:")
                || path.startsWith("fkfield:")
                || path.startsWith("repo:")
                || path.startsWith("svc:")
                || path.startsWith("ctrl:");
    }

    private RiskLevel getOverallRisk(ImpactResult result) {
        if (result.countByRisk(RiskLevel.HIGH) > 0) return RiskLevel.HIGH;
        if (result.countByRisk(RiskLevel.MEDIUM) > 0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private void printSection(String title, List<AffectedApi> apis, String color) {
        if (apis.isEmpty()) return;

        System.out.println(color + BOLD + title + RESET);
        System.out.println();

        for (int i = 0; i < apis.size(); i++) {
            AffectedApi api = apis.get(i);

            System.out.println(color + "  [" + (i + 1) + "] " + api.getApiId() + RESET);
            System.out.println("      Cause : " + api.getCause());
            System.out.println("      Risk  : " + api.getRiskLevel());

            printFkImpact(api);

            System.out.println(msg("      상세 경로:", "      Details:"));
            printFriendlyPath(api.getImpactPath());
            System.out.println();
        }
    }

    private void printFkImpact(AffectedApi api) {
        SchemaChange cause = api.getCause();

        if (!cause.isFkChange()
                && findFirstPathValue(api.getImpactPath(), "fkfield:") == null) {
            return;
        }

        String fkField = findFirstPathValue(api.getImpactPath(), "fkfield:");
        String relationText = fkField != null ? fkField : msg("JPA 연관관계", "JPA Relation");

        System.out.println(CYAN + msg("      FK 연관관계 손상 가능", "      FK Relation Broken") + RESET);

        if (cause.getReferencedTable() != null) {
            System.out.println("        " + relationText
                    + msg(" -> 참조 관계 확인: ", " -> check relation with ")
                    + cause.getReferencedTable());
        } else {
            System.out.println("        " + relationText
                    + msg(" 관계가 손상될 수 있습니다.", " relation may be broken."));
        }

        System.out.println();
    }

    private void printFriendlyPath(List<String> path) {
        for (int i = 0; i < path.size(); i++) {
            String item = path.get(i);
            String indent = "        " + "  ".repeat(i);
            String label = getFriendlyLabel(item);
            String value = removePrefix(item);

            System.out.printf("%s-> %-10s : %s%n", indent, label, value);
        }
    }

    private String getFriendlyLabel(String item) {
        if (item.startsWith("col:")) return msg("컬럼", "Column");
        if (item.startsWith("field:") || item.startsWith("fkfield:")) return "Entity";
        if (item.startsWith("repo:")) return msg("Repository", "Repo");
        if (item.startsWith("svc:")) return "Service";
        if (item.startsWith("ctrl:")) return "Controller";
        if (item.startsWith("api:")) return "API";
        return "Path";
    }

    private void printSuggestedAction(AffectedApi api) {
        ChangeType type = api.getCause().getChangeType();

        System.out.println(msg("      수정 가이드:", "      Fix Guide:"));

        switch (type) {
            case DROP_COLUMN:
                System.out.println(msg("        - Entity 필드 제거/수정", "        - Remove/update Entity field"));
                System.out.println(msg("        - Repository 메서드 확인", "        - Check Repository methods"));
                System.out.println(msg("        - DTO/Service 참조 제거", "        - Remove DTO/Service refs"));
                break;

            case RENAME_COLUMN:
                System.out.println(msg("        - @Column 매핑 수정", "        - Update @Column mapping"));
                System.out.println(msg("        - Entity/Repo 이름 확인", "        - Rename Entity/Repo methods"));
                System.out.println(msg("        - Query 문자열 수정", "        - Update query strings"));
                break;

            case MODIFY_TYPE:
                System.out.println(msg("        - Java 타입 맞추기", "        - Match Java type"));
                System.out.println(msg("        - DTO/JSON 타입 확인", "        - Check DTO/JSON types"));
                System.out.println(msg("        - 타입 변환 로직 검토", "        - Review type conversion"));
                break;

            case ADD_NOT_NULL:
                System.out.println(msg("        - NULL 데이터 확인", "        - Check NULL data"));
                System.out.println(msg("        - 필수 입력 검증", "        - Validate required input"));
                System.out.println(msg("        - DEFAULT 값 검토", "        - Consider DEFAULT value"));
                break;

            case ADD_UNIQUE:
                System.out.println(msg("        - 중복 데이터 확인", "        - Check duplicate data"));
                System.out.println(msg("        - 중복 검증 로직 확인", "        - Validate unique logic"));
                break;

            case ADD_COLUMN:
                System.out.println(msg("        - 필수 필드 여부 확인", "        - Check required field"));
                System.out.println(msg("        - Entity/DTO 반영", "        - Update Entity/DTO"));
                System.out.println(msg("        - NULL/default 확인", "        - Review NULL/default"));
                break;

            case DROP_TABLE:
                System.out.println(msg("        - Entity/Repo 사용 제거", "        - Remove Entity/Repo usage"));
                System.out.println(msg("        - FK 매핑 확인", "        - Check FK mappings"));
                System.out.println(msg("        - Service 로직 수정", "        - Update Service logic"));
                break;

            case RENAME_TABLE:
                System.out.println(msg("        - @Table 매핑 수정", "        - Update @Table mapping"));
                System.out.println(msg("        - Native Query 확인", "        - Check native queries"));
                System.out.println(msg("        - Service 검토", "        - Review services"));
                break;

            case DROP_FOREIGN_KEY:
                System.out.println(msg("        - JPA 관계 확인", "        - Check JPA relations"));
                System.out.println(msg("        - 참조 무결성 검토", "        - Review integrity"));
                System.out.println(msg("        - JOIN 쿼리 확인", "        - Check JOIN queries"));
                break;

            case DROP_FK_COLUMN:
                System.out.println(msg("        - @JoinColumn 확인", "        - Check @JoinColumn"));
                System.out.println(msg("        - 연관관계 수정", "        - Update relations"));
                System.out.println(msg("        - JOIN 쿼리 검토", "        - Review JOIN queries"));
                System.out.println(msg("        - NullPointer 확인", "        - Check NullPointer"));
                break;

            case MODIFY_FK_REFERENCE:
                System.out.println(msg("        - FK와 Entity 관계 맞추기", "        - Match FK and Entity"));
                System.out.println(msg("        - @JoinColumn 수정", "        - Update @JoinColumn"));
                System.out.println(msg("        - JOIN 결과 검토", "        - Review JOIN results"));
                break;

            case ADD_FOREIGN_KEY:
                System.out.println(msg("        - FK 제약 확인", "        - Check FK constraints"));
                System.out.println(msg("        - 참조 Entity 검증", "        - Validate referenced Entity"));
                System.out.println(msg("        - 테스트 데이터 확인", "        - Check test data"));
                break;

            default:
                System.out.println(msg("        - 영향 경로 검토", "        - Review impact path"));
                break;
        }
    }

    private void printRecommendedFix(AffectedApi api) {
        SchemaChange cause = api.getCause();
        ChangeType type = cause.getChangeType();

        System.out.println(msg("      추천 수정:", "      Recommended Fix:"));

        switch (type) {
            case DROP_COLUMN:
                printDropColumnFix(api, cause);
                break;

            case RENAME_COLUMN:
                printRenameColumnFix(api, cause);
                break;

            case MODIFY_TYPE:
                System.out.println(msg(
                        "        - DB 컬럼 타입과 Entity 필드 타입 맞추기",
                        "        - Match DB column type with Entity field type."
                ));
                break;

            case ADD_NOT_NULL:
                System.out.println(msg(
                        "        - 필수값이 항상 입력되도록 수정",
                        "        - Ensure required value is always provided."
                ));
                break;

            case DROP_TABLE:
                System.out.println(msg(
                        "        - 관련 의존성 제거: ",
                        "        - Remove dependencies related to "
                ) + cause.getTableName());
                break;

            case RENAME_TABLE:
                if (cause.getNewName() != null) {
                    System.out.println(msg(
                            "        - @Table 매핑 변경: ",
                            "        - Update @Table mapping to "
                    ) + cause.getNewName());
                } else {
                    System.out.println(msg(
                            "        - 변경된 테이블명에 맞게 수정",
                            "        - Update changed table name"
                    ));
                }
                break;

            case DROP_FOREIGN_KEY:
                System.out.println(msg(
                        "        - FK 제거 후 JPA 관계 유지 여부 재검토",
                        "        - Reconsider JPA relation after FK removal."
                ));
                break;

            case DROP_FK_COLUMN:
                System.out.println(msg(
                        "        - @JoinColumn 제거 또는 대체 컬럼 지정",
                        "        - Remove or replace @JoinColumn mapping."
                ));
                break;

            case MODIFY_FK_REFERENCE:
                System.out.println(msg(
                        "        - 연관 Entity 타입 수정 검토",
                        "        - Update related Entity type."
                ));
                break;

            case ADD_FOREIGN_KEY:
                System.out.println(msg(
                        "        - 저장 전 참조 Entity 검증",
                        "        - Validate referenced Entity before save."
                ));
                break;

            case ADD_UNIQUE:
                System.out.println(msg(
                        "        - 중복 검사 로직 추가",
                        "        - Add duplicate check logic."
                ));
                break;

            case ADD_COLUMN:
                System.out.println(msg(
                        "        - 필요 시 Entity/DTO에 새 필드 추가",
                        "        - Add new field to Entity/DTO if needed."
                ));
                break;

            default:
                System.out.println(msg(
                        "        - 상위 영향 노드부터 검토",
                        "        - Review upper impact nodes."
                ));
                break;
        }
    }

    private void printDropColumnFix(AffectedApi api, SchemaChange cause) {
        String repo = findFirstPathValue(api.getImpactPath(), "repo:");

        if (repo != null && repo.contains("findByEmail")) {
            System.out.println(msg(
                    "        - findByEmail() 사용 제거 또는 대체",
                    "        - Replace findByEmail() usage."
            ));
            return;
        }

        String field = findFirstPathValue(api.getImpactPath(), "field:", "fkfield:");

        if (field != null) {
            System.out.println(msg("        - 필드 제거/수정: ", "        - Remove/update field : ") + field);
        } else {
            System.out.println(msg("        - 컬럼 참조 제거: ", "        - Remove column reference : ")
                    + cause.getTableName()
                    + "."
                    + cause.getColumnName());
        }
    }

    private void printRenameColumnFix(AffectedApi api, SchemaChange cause) {
        String field = findFirstPathValue(api.getImpactPath(), "field:", "fkfield:");

        if (cause.getNewName() != null) {
            System.out.println(msg("        - @Column 변경: ", "        - Update @Column to ")
                    + cause.getNewName());
        } else if (field != null) {
            System.out.println(msg("        - @Column 매핑 수정: ", "        - Update @Column mapping for ")
                    + field);
        } else {
            System.out.println(msg("        - Entity 매핑 수정", "        - Update Entity mapping"));
        }
    }

    private String findFirstPathValue(List<String> path, String... prefixes) {
        for (String prefix : prefixes) {
            String value = findPathValue(path, prefix);

            if (value != null) return value;
        }

        return null;
    }

    private String findPathValue(List<String> path, String prefix) {
        for (String item : path) {
            if (item.startsWith(prefix)) {
                return removePrefix(item);
            }
        }

        return null;
    }

    private String removePrefix(String item) {
        int idx = item.indexOf(":");

        if (idx == -1) return item;

        return item.substring(idx + 1);
    }

    private void printFooter(ImpactResult result) {
        System.out.println(BOLD + "========================================" + RESET);

        System.out.printf("  Total HIGH   : %s%d%s%n",
                RED, result.countByRisk(RiskLevel.HIGH), RESET);

        System.out.printf("  Total MEDIUM : %s%d%s%n",
                YELLOW, result.countByRisk(RiskLevel.MEDIUM), RESET);

        System.out.printf("  Total LOW    : %s%d%s%n",
                GREEN, result.countByRisk(RiskLevel.LOW), RESET);

        System.out.println(BOLD + "========================================" + RESET);
        System.out.println();

        if (result.hasHighRisk()) {
            System.out.println(RED + msg(
                    "  HIGH 위험 항목이 감지되었습니다. 병합 전 검토가 필요합니다.",
                    "  HIGH risk detected. Review before merge."
            ) + RESET);
            System.out.println();
        }
    }

    private void printCauseGuideSummary(ImpactResult result) {
        Map<String, List<AffectedApi>> causeMap = new LinkedHashMap<>();

        for (AffectedApi api : result.getAffectedApis()) {
            String cause = api.getCause().toString();

            if (!causeMap.containsKey(cause)) {
                causeMap.put(cause, new ArrayList<>());
            }

            causeMap.get(cause).add(api);
        }

        List<Map.Entry<String, List<AffectedApi>>> entries =
                new ArrayList<>(causeMap.entrySet());

        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        System.out.println(BOLD + msg("영향 원인별 수정 가이드", "Fix Guide By Cause") + RESET);

        int rank = 1;

        for (Map.Entry<String, List<AffectedApi>> entry : entries) {
            AffectedApi representativeApi = entry.getValue().get(0);

            System.out.println("  [" + rank + "] " + entry.getKey()
                    + msg(" -> 영향 API ", " -> APIs : ")
                    + entry.getValue().size());

            printSuggestedAction(representativeApi);
            printRecommendedFix(representativeApi);

            System.out.println();
            rank++;
        }
    }
}