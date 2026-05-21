package schemaguard.report;

import schemaguard.model.*;

import java.util.*;

public class ConsoleReporter implements ReportGenerator {

    private static final String RESET  = "\u001B[0m";
    private static final String RED    = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN  = "\u001B[32m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GRAY   = "\u001B[90m";
    private static final String BOLD   = "\u001B[1m";

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
            System.out.println(GREEN + msg("  영향을 받는 API가 없습니다.", "  No affected APIs.") + RESET);
            printFooter(result);
            return;
        }

        printCauseGuideSummary(result);
        printCoreImpactNodes(result);
        printDetailPaths(result);
        printFooter(result);
    }

    private void printHeader() {
        System.out.println();
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println(BOLD + "        SchemaGuard Report              " + RESET);
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println();
    }

    private void printSummary(ImpactResult result) {
        RiskLevel overallRisk = getOverallRisk(result);

        System.out.println(BOLD + msg("📌 1. 요약", "1. Summary") + RESET);
        System.out.println(msg("  - 영향 API 수 : ", "  - Affected APIs : ") + result.getAffectedApis().size());
        System.out.println(msg("  - 전체 위험도 : ", "  - Overall Risk  : ") + colorRisk(overallRisk));
        System.out.println();
    }

    private void printCauseGuideSummary(ImpactResult result) {
        Map<String, List<AffectedApi>> causeMap = new LinkedHashMap<>();

        for (AffectedApi api : result.getAffectedApis()) {
            causeMap.computeIfAbsent(api.getCause().toString(), k -> new ArrayList<>()).add(api);
        }

        List<Map.Entry<String, List<AffectedApi>>> entries = new ArrayList<>(causeMap.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        System.out.println(BOLD + msg("⚠️ 2. 영향 원인과 수정 가이드", "2. Causes and Fix Guide") + RESET);

        int rank = 1;
        for (Map.Entry<String, List<AffectedApi>> entry : entries) {
            AffectedApi api = entry.getValue().get(0);

            System.out.println("  [" + rank + "] " + api.getCause()
                    + msg(" -> 영향 API ", " -> Affected APIs ")
                    + entry.getValue().size());

            printSuggestedAction(api);
            printRecommendedFix(api);

            System.out.println();
            rank++;
        }
    }

    private void printSuggestedAction(AffectedApi api) {
        ChangeType type = api.getCause().getChangeType();

        System.out.println(msg("      수정 가이드:", "      Fix Guide:"));

        switch (type) {
            case DROP_COLUMN -> {
                guide("삭제된 컬럼과 매핑된 Entity 필드 확인", "Check Entity field mapped to dropped column");
                guide("해당 필드를 사용하는 Repository 메서드 확인", "Check Repository methods using the field");
                guide("Service/DTO/Controller 참조 제거 또는 수정", "Remove or update Service/DTO/Controller references");
            }
            case RENAME_COLUMN -> {
                guide("@Column 매핑 확인", "Check @Column mapping");
                guide("Repository 메서드명과 쿼리 문자열 확인", "Check Repository method names and query strings");
                guide("DTO/Service/Controller의 필드 참조 확인", "Check field references in DTO/Service/Controller");
            }
            case MODIFY_TYPE -> {
                guide("DB 컬럼 타입과 Java 필드 타입 비교", "Compare DB column type and Java field type");
                guide("DTO/Request/Response 타입 확인", "Check DTO/Request/Response types");
                guide("타입 변환 로직 검토", "Review type conversion logic");
            }
            case ADD_NOT_NULL -> {
                guide("기존 NULL 데이터 확인", "Check existing NULL data");
                guide("필수 입력 검증 로직 확인", "Check required input validation");
                guide("DEFAULT 값 필요 여부 검토", "Review whether DEFAULT value is needed");
            }
            case ADD_UNIQUE -> {
                guide("기존 중복 데이터 확인", "Check existing duplicate data");
                guide("생성/수정 API의 중복 검증 로직 확인", "Check duplicate validation logic in create/update APIs");
                guide("중복 예외 처리 확인", "Check duplicate exception handling");
            }
            case ADD_COLUMN -> {
                guide("새 컬럼이 필수 필드인지 확인", "Check whether the new column is required");
                guide("Entity/DTO 반영 여부 확인", "Check Entity/DTO updates");
                guide("생성/수정 API에서 새 컬럼 처리 여부 확인", "Check whether create/update APIs handle the new column");
            }
            case DROP_TABLE -> {
                guide("삭제된 테이블과 연결된 Entity/Repository 확인", "Check Entity/Repository related to dropped table");
                guide("관련 FK/JOIN/Native Query 확인", "Check related FK/JOIN/Native Query usage");
                guide("Service/Controller 의존성 제거 또는 대체", "Remove or replace Service/Controller dependencies");
            }
            case RENAME_TABLE -> {
                guide("@Table 매핑 확인", "Check @Table mapping");
                guide("Native Query의 테이블명 확인", "Check table names in native queries");
                guide("관련 Repository/Service 영향 확인", "Check related Repository/Service impact");
            }
            case DROP_FOREIGN_KEY -> {
                guide("JPA 연관관계 확인", "Check JPA relations");
                guide("참조 무결성 검토", "Review referential integrity");
                guide("JOIN 쿼리와 Cascade 설정 확인", "Check JOIN queries and cascade settings");
            }
            case DROP_FK_COLUMN -> {
                guide("@JoinColumn 매핑 확인", "Check @JoinColumn mapping");
                guide("연관 Entity 필드 확인", "Check related Entity field");
                guide("JOIN 쿼리와 NullPointer 가능성 검토", "Review JOIN queries and possible NullPointer issues");
            }
            case MODIFY_FK_REFERENCE -> {
                guide("변경된 FK 참조 대상 확인", "Check changed FK reference target");
                guide("@JoinColumn referencedColumnName 확인", "Check @JoinColumn referencedColumnName");
                guide("기존 데이터의 참조 무결성 확인", "Check referential integrity of existing data");
            }
            case ADD_FOREIGN_KEY -> {
                guide("FK 제약 조건 확인", "Check FK constraints");
                guide("참조 Entity 존재 여부 검증", "Validate referenced Entity existence");
                guide("기존 데이터가 FK 제약을 만족하는지 확인", "Check whether existing data satisfies FK constraints");
            }
            default -> guide("영향 경로 검토", "Review impact path");
        }
    }

    private void printRecommendedFix(AffectedApi api) {
        SchemaChange cause = api.getCause();
        ChangeType type = cause.getChangeType();

        String target = targetName(cause);
        Node fieldNode = findFirstNode(api.getImpactNodes(), "field:", "fkfield:");
        Node repoNode = findFirstNode(api.getImpactNodes(), "repo:");
        Node svcNode = findFirstNode(api.getImpactNodes(), "svc:");

        System.out.println(msg("      추천 수정:", "      Recommended Fixes:"));

        switch (type) {
            case DROP_COLUMN -> {
                fix("삭제된 컬럼 참조 제거 또는 대체: " + target,
                        "Remove or replace references to dropped column: " + target);

                if (fieldNode != null) {
                    fix("매핑된 Entity 필드 제거 또는 수정: " + removePrefix(fieldNode.getId()),
                            "Remove or update mapped Entity field: " + removePrefix(fieldNode.getId()));
                }

                if (repoNode != null) {
                    fix("해당 컬럼을 사용하는 Repository 메서드 수정: " + removePrefix(repoNode.getId()),
                            "Update Repository method using the column: " + removePrefix(repoNode.getId()));
                }

                fix("해당 컬럼 기반 조회/저장/검증 로직 제거 또는 대체",
                        "Remove or replace lookup/save/validation logic based on the column");
            }
            case RENAME_COLUMN -> {
                fix("변경된 컬럼명에 맞게 매핑 수정: " + target,
                        "Update mapping for renamed column: " + target);

                if (cause.getNewName() != null) {
                    fix("새 컬럼명 반영: " + cause.getNewName(),
                            "Apply new column name: " + cause.getNewName());
                }

                if (fieldNode != null) {
                    fix("Entity 필드의 @Column name 확인: " + removePrefix(fieldNode.getId()),
                            "Check @Column name of Entity field: " + removePrefix(fieldNode.getId()));
                }

                fix("쿼리 문자열과 Repository 메서드명 동기화",
                        "Synchronize query strings and Repository method names");
            }
            case MODIFY_TYPE -> {
                fix("변경된 타입에 맞게 Entity 필드 타입 수정: " + target,
                        "Update Entity field type according to changed type: " + target);
                fix("DTO/Request/Response 타입도 함께 확인",
                        "Check DTO/Request/Response types together");
                fix("타입 변환 로직과 테스트 코드 수정",
                        "Update type conversion logic and tests");
            }
            case ADD_NOT_NULL -> {
                fix("필수값이 항상 입력되도록 검증 로직 추가: " + target,
                        "Add validation to ensure required value is provided: " + target);
                fix("기존 NULL 데이터 처리 마이그레이션 추가",
                        "Add migration to handle existing NULL data");
                fix("생성/수정 API에서 필수값 누락 케이스 확인",
                        "Check missing required value cases in create/update APIs");
            }
            case ADD_UNIQUE -> {
                fix("기존 중복 데이터 정리 후 UNIQUE 적용: " + target,
                        "Clean duplicate data before applying UNIQUE: " + target);
                fix("생성/수정 API에 중복 검사 추가",
                        "Add duplicate checks to create/update APIs");
                fix("중복 발생 시 예외 처리 추가",
                        "Add exception handling for duplicate values");
            }
            case ADD_COLUMN -> {
                fix("새 컬럼을 Entity/DTO에 반영할지 결정: " + target,
                        "Decide whether to reflect new column in Entity/DTO: " + target);
                fix("필수 컬럼이면 기본값 또는 입력 검증 추가",
                        "Add default value or input validation if the column is required");
                fix("생성/수정 API에서 새 컬럼 처리 여부 확인",
                        "Check whether create/update APIs handle the new column");
            }
            case DROP_TABLE -> {
                fix("삭제된 테이블과 연결된 Entity/Repository 제거 또는 대체: " + target,
                        "Remove or replace Entity/Repository related to dropped table: " + target);
                fix("삭제된 테이블을 참조하는 Service 로직 수정",
                        "Update Service logic referencing the dropped table");
                fix("관련 FK/JOIN/Native Query 제거 또는 변경",
                        "Remove or update related FK/JOIN/Native Query usage");
            }
            case RENAME_TABLE -> {
                fix("변경된 테이블명에 맞게 @Table 매핑 수정: " + target,
                        "Update @Table mapping according to renamed table: " + target);

                if (cause.getNewName() != null) {
                    fix("새 테이블명 반영: " + cause.getNewName(),
                            "Apply new table name: " + cause.getNewName());
                }

                fix("Native Query에 직접 작성된 테이블명 수정",
                        "Update hard-coded table names in native queries");
                fix("테스트 코드와 문서의 테이블명도 확인",
                        "Check table names in tests and documentation");
            }
            case DROP_FOREIGN_KEY -> {
                fix("FK 제거 후 JPA 연관관계 유지 여부 재검토: " + target,
                        "Reconsider whether to keep JPA relation after FK removal: " + target);
                fix("Cascade 옵션과 orphanRemoval 설정 확인",
                        "Check cascade options and orphanRemoval settings");
                fix("FK 없이도 JOIN/조회 로직이 정상 동작하는지 테스트",
                        "Test whether JOIN/query logic works without FK constraint");
            }
            case DROP_FK_COLUMN -> {
                fix("삭제된 FK 컬럼과 연결된 @JoinColumn 제거 또는 대체: " + target,
                        "Remove or replace @JoinColumn mapped to dropped FK column: " + target);

                if (fieldNode != null) {
                    fix("연관 Entity 필드 제거 또는 관계 재설계: " + removePrefix(fieldNode.getId()),
                            "Remove related Entity field or redesign relation: " + removePrefix(fieldNode.getId()));
                }

                fix("해당 FK 컬럼을 사용하는 Repository 쿼리 수정",
                        "Update Repository queries using the FK column");
                fix("Service 로직에서 연관 객체 NullPointer 가능성 확인",
                        "Check possible NullPointer issues in Service logic");
            }
            case MODIFY_FK_REFERENCE -> {
                fix("변경된 참조 대상에 맞게 Entity 관계 수정: " + target,
                        "Update Entity relation according to changed FK reference: " + target);
                fix("@JoinColumn referencedColumnName 확인",
                        "Check @JoinColumn referencedColumnName");
                fix("기존 데이터의 참조 무결성 확인",
                        "Check referential integrity of existing data");
            }
            case ADD_FOREIGN_KEY -> {
                fix("저장 전에 참조 대상 존재 여부 검증: " + target,
                        "Validate referenced target existence before save: " + target);
                fix("기존 데이터 중 FK 제약 위반 데이터 확인",
                        "Check existing data that violates FK constraints");
                fix("테스트 데이터에 참조 대상 데이터 추가",
                        "Add referenced data to test fixtures");
            }
            default -> {
                fix("상위 영향 노드부터 순서대로 검토",
                        "Review upper impact nodes first");
            }
        }

        if (svcNode != null) {
            fix("연결된 Service 흐름 확인: " + removePrefix(svcNode.getId()),
                    "Check related Service flow: " + removePrefix(svcNode.getId()));
        }
    }

    private void printCoreImpactNodes(ImpactResult result) {
        Map<String, Integer> nodeCountMap = new LinkedHashMap<>();

        for (AffectedApi api : result.getAffectedApis()) {
            for (Node node : api.getImpactNodes()) {
                if (isCoreNode(node.getId())) {
                    String key = removePrefix(node.getId());
                    nodeCountMap.put(key, nodeCountMap.getOrDefault(key, 0) + 1);
                }
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(nodeCountMap.entrySet());
        entries.removeIf(e -> e.getValue() < 2);
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        if (entries.isEmpty()) return;

        System.out.println(BOLD + msg("🔥 3. 핵심 영향 노드", "3. Core Impact Nodes") + RESET);

        int limit = Math.min(entries.size(), 5);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            System.out.println("  - " + entry.getKey()
                    + " -> "
                    + entry.getValue()
                    + msg("개 API에 영향", " affected APIs"));
        }

        System.out.println();
    }

    private void printDetailPaths(ImpactResult result) {
        System.out.println(BOLD + msg("🛠️ 4. 상세 경로", "4. Detailed Paths") + RESET);

        List<AffectedApi> apis = result.getAffectedApis();

        for (int i = 0; i < apis.size(); i++) {
            AffectedApi api = apis.get(i);

            System.out.println();
            System.out.println(colorByRisk(api.getRiskLevel())
                    + "[" + (i + 1) + "] " + api.getApiId()
                    + RESET);

            System.out.println("      Cause : " + api.getCause());
            System.out.println("      Risk  : " + api.getRiskLevel());

            printFkImpact(api);

            System.out.println(msg("      상세 경로:", "      Detail Path:"));
            printFriendlyPath(api.getImpactNodes());
        }

        System.out.println();
    }

    private void printFkImpact(AffectedApi api) {
        SchemaChange cause = api.getCause();

        if (!cause.isFkChange()
                && findFirstNode(api.getImpactNodes(), "fkfield:") == null) {
            return;
        }

        Node fkNode = findFirstNode(api.getImpactNodes(), "fkfield:");
        String relationText = fkNode != null
                ? removePrefix(fkNode.getId())
                : msg("JPA 연관관계", "JPA relation");

        System.out.println(CYAN + msg("      FK 연관관계 손상 가능", "      FK relation may be broken") + RESET);

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

    private void printFriendlyPath(List<Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);

            String indent = "        " + "  ".repeat(i);
            String label = getFriendlyLabel(node.getId());
            String value = removePrefix(node.getId());

            System.out.printf("%s-> %s : %s%n", indent, label, value);

            if (node.hasLocation()) {
                System.out.printf("%s   %s(%s)%s%n",
                        indent + "  ",
                        GRAY,
                        node.getLocationString(),
                        RESET);
            }
        }
    }

    private void printFooter(ImpactResult result) {
        System.out.println(BOLD + "========================================" + RESET);
        System.out.printf("  Total HIGH   : %s%d%s%n", RED, result.countByRisk(RiskLevel.HIGH), RESET);
        System.out.printf("  Total MEDIUM : %s%d%s%n", YELLOW, result.countByRisk(RiskLevel.MEDIUM), RESET);
        System.out.printf("  Total LOW    : %s%d%s%n", GREEN, result.countByRisk(RiskLevel.LOW), RESET);
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

    private void guide(String ko, String en) {
        System.out.println("        - " + msg(ko, en));
    }

    private void fix(String ko, String en) {
        System.out.println("        - " + msg(ko, en));
    }

    private String targetName(SchemaChange cause) {
        String table = cause.getTableName();
        String column = cause.getColumnName();

        if (table != null && column != null) {
            return table + "." + column;
        }

        if (table != null) {
            return table;
        }

        if (column != null) {
            return column;
        }

        return cause.toString();
    }

    private boolean isCoreNode(String id) {
        return id.startsWith("field:")
            || id.startsWith("fkfield:")
            || id.startsWith("repo:")
            || id.startsWith("svc:")
            || id.startsWith("ctrl:");
    }

    private RiskLevel getOverallRisk(ImpactResult result) {
        if (result.countByRisk(RiskLevel.HIGH) > 0) return RiskLevel.HIGH;
        if (result.countByRisk(RiskLevel.MEDIUM) > 0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private String colorRisk(RiskLevel risk) {
        return switch (risk) {
            case HIGH -> RED + "HIGH" + RESET;
            case MEDIUM -> YELLOW + "MEDIUM" + RESET;
            case LOW -> GREEN + "LOW" + RESET;
        };
    }

    private String colorByRisk(RiskLevel risk) {
        return switch (risk) {
            case HIGH -> RED;
            case MEDIUM -> YELLOW;
            case LOW -> GREEN;
        };
    }

    private String getFriendlyLabel(String id) {
        if (id.startsWith("col:")) return msg("컬럼", "Column");
        if (id.startsWith("field:") || id.startsWith("fkfield:")) return "Entity";
        if (id.startsWith("repo:")) return "Repository";
        if (id.startsWith("svc:")) return "Service";
        if (id.startsWith("ctrl:")) return "Controller";
        if (id.startsWith("api:")) return "API";
        return "Node";
    }

    private Node findFirstNode(List<Node> nodes, String... prefixes) {
        for (String prefix : prefixes) {
            for (Node node : nodes) {
                if (node.getId().startsWith(prefix)) {
                    return node;
                }
            }
        }
        return null;
    }

    private String removePrefix(String item) {
        int idx = item.indexOf(":");
        return idx == -1 ? item : item.substring(idx + 1);
    }
}