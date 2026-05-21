package schemaguard.report;

import schemaguard.model.AffectedApi;
import schemaguard.model.ChangeType;
import schemaguard.model.ImpactResult;
import schemaguard.model.RiskLevel;

import java.util.List;

/**
 * 분석 결과를 콘솔(stdout)에 사람이 읽기 좋은 형태로 출력한다.
 */
public class ConsoleReporter implements ReportGenerator {

    private static final String RESET  = "\u001B[0m";
    private static final String RED    = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN  = "\u001B[32m";
    private static final String BOLD   = "\u001B[1m";

    @Override
    public void generate(ImpactResult result) {
        printHeader();
        printSummary(result);

        if (result.isEmpty()) {
            System.out.println(GREEN + "  ✅ 영향을 받는 API가 없습니다." + RESET);
            printFooter(result);
            return;
        }

        printSection("🔴 HIGH RISK", result.getByRisk(RiskLevel.HIGH), RED);
        printSection("🟡 MEDIUM RISK", result.getByRisk(RiskLevel.MEDIUM), YELLOW);
        printSection("🟢 LOW RISK", result.getByRisk(RiskLevel.LOW), GREEN);

        printFooter(result);
    }

    private void printHeader() {
        System.out.println();
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println(BOLD + "   SchemaGuard Analysis Report          " + RESET);
        System.out.println(BOLD + "========================================" + RESET);
        System.out.println();
    }

    private void printSummary(ImpactResult result) {
        int total = result.getAffectedApis().size();

        String mainCause = result.isEmpty()
                ? "없음"
                : result.getAffectedApis().get(0).getCause().toString();

        RiskLevel overallRisk = getOverallRisk(result);

        System.out.println(BOLD + "분석 요약" + RESET);
        System.out.println("  SchemaGuard 분석 결과, 총 " + total + "개의 API가 영향을 받을 수 있습니다.");
        System.out.println("  주요 원인: " + mainCause);
        System.out.println("  전체 위험도: " + overallRisk);
        System.out.println();
    }

    private RiskLevel getOverallRisk(ImpactResult result) {
        if (result.countByRisk(RiskLevel.HIGH) > 0) {
            return RiskLevel.HIGH;
        }

        if (result.countByRisk(RiskLevel.MEDIUM) > 0) {
            return RiskLevel.MEDIUM;
        }

        return RiskLevel.LOW;
    }

    private void printSection(String title, List<AffectedApi> apis, String color) {
        if (apis.isEmpty()) return;

        System.out.println(color + BOLD + title + RESET);
        System.out.println();

        for (int i = 0; i < apis.size(); i++) {
            AffectedApi api = apis.get(i);

            System.out.println(color + "  [" + (i + 1) + "] ❌ " + api.getApiId() + RESET);
            System.out.println("      Cause  : " + api.getCause());
            System.out.println("      Risk   : " + api.getRiskLevel());

            System.out.println("      영향 흐름:");
            System.out.println("        " + buildSummaryFlow(api));
            System.out.println();

            System.out.println("      상세 경로:");
            printFriendlyPath(api.getImpactPath());
            System.out.println();

            printSuggestedAction(api);
            System.out.println();
        }
    }

    private String buildSummaryFlow(AffectedApi api) {
        List<String> path = api.getImpactPath();

        String column = findPathValue(path, "col:");
        String entity = findFirstPathValue(path, "field:", "fkfield:");
        String endpoint = findPathValue(path, "api:");

        if (column != null && entity != null && endpoint != null) {
            return column + " 변경이 "
                    + entity + "를 거쳐 "
                    + endpoint + " API까지 영향을 줍니다.";
        }

        if (entity != null && endpoint != null) {
            return entity + " 변경이 "
                    + endpoint + " API까지 영향을 줍니다.";
        }

        return "해당 변경사항이 API까지 영향을 줄 수 있습니다.";
    }

    private void printFriendlyPath(List<String> path) {
        for (int i = 0; i < path.size(); i++) {
            String item = path.get(i);
            String indent = "        " + "  ".repeat(i);
            String label = getFriendlyLabel(item);
            String value = removePrefix(item);

            System.out.printf("%s→ %-10s : %s%n", indent, label, value);
        }
    }

    private String getFriendlyLabel(String item) {
        if (item.startsWith("col:")) {
            return "DB Column";
        }

        if (item.startsWith("field:") || item.startsWith("fkfield:")) {
            return "Entity";
        }

        if (item.startsWith("repo:")) {
            return "Repository";
        }

        if (item.startsWith("svc:")) {
            return "Service";
        }

        if (item.startsWith("ctrl:")) {
            return "Controller";
        }

        if (item.startsWith("api:")) {
            return "API";
        }

        return "Path";
    }

    private void printSuggestedAction(AffectedApi api) {
        ChangeType type = api.getCause().getChangeType();

        System.out.println("      수정 가이드:");

        switch (type) {
            case DROP_COLUMN:
                System.out.println("        - 삭제된 컬럼을 사용하는 Entity 필드를 제거하거나 수정하세요.");
                System.out.println("        - Repository 메서드(findBy...) 사용 여부를 확인하세요.");
                System.out.println("        - DTO 및 Service 로직에서 해당 컬럼 참조를 제거하세요.");
                break;

            case RENAME_COLUMN:
                System.out.println("        - @Column(name=...) 매핑 값을 수정하세요.");
                System.out.println("        - Entity 필드명 및 Repository 메서드명을 확인하세요.");
                System.out.println("        - JPQL / QueryDSL / Native Query 문자열을 수정하세요.");
                break;

            case MODIFY_TYPE:
                System.out.println("        - 컬럼 타입 변경에 맞게 Java 타입을 수정하세요.");
                System.out.println("        - DTO 및 JSON 직렬화 타입을 확인하세요.");
                System.out.println("        - 타입 변환 로직이 필요한지 검토하세요.");
                break;

            case ADD_NOT_NULL:
                System.out.println("        - 기존 NULL 데이터 존재 여부를 확인하세요.");
                System.out.println("        - INSERT/UPDATE 로직에서 필수값 누락 여부를 확인하세요.");
                System.out.println("        - 필요한 경우 기본값(DEFAULT) 설정을 고려하세요.");
                break;

            case ADD_UNIQUE:
                System.out.println("        - 기존 데이터에 중복 값이 있는지 확인하세요.");
                System.out.println("        - INSERT/UPDATE 시 중복 값 저장 가능성을 검토하세요.");
                System.out.println("        - 회원가입, 게시글 생성 등 저장 로직의 중복 검증을 확인하세요.");
                break;

            case ADD_COLUMN:
                System.out.println("        - 새 컬럼이 필수값인지 확인하세요.");
                System.out.println("        - Entity, DTO, 저장 로직에 새 필드 반영이 필요한지 검토하세요.");
                System.out.println("        - 기본값 또는 NULL 허용 여부를 확인하세요.");
                break;

            case DROP_TABLE:
                System.out.println("        - 관련 Entity 및 Repository 제거 여부를 검토하세요.");
                System.out.println("        - FK 관계 및 연관 매핑을 확인하세요.");
                System.out.println("        - 해당 테이블을 참조하는 Service 로직을 수정하세요.");
                break;

            case RENAME_TABLE:
                System.out.println("        - @Table(name=...) 매핑 값을 수정하세요.");
                System.out.println("        - Native Query 또는 JPQL에서 기존 테이블명을 사용하는지 확인하세요.");
                System.out.println("        - 관련 Repository 및 Service 로직을 검토하세요.");
                break;

            case DROP_FOREIGN_KEY:
                System.out.println("        - 연관 Entity 매핑(@ManyToOne, @OneToMany)을 확인하세요.");
                System.out.println("        - FK 제거 후 참조 무결성 보장이 필요한지 검토하세요.");
                System.out.println("        - JOIN 기반 Repository 쿼리에 영향이 없는지 확인하세요.");
                break;

            case DROP_FK_COLUMN:
                System.out.println("        - @JoinColumn 매핑이 깨질 수 있으므로 Entity 관계를 확인하세요.");
                System.out.println("        - @ManyToOne / @OneToMany 연관관계를 수정하세요.");
                System.out.println("        - JOIN 기반 Repository 쿼리 영향을 확인하세요.");
                System.out.println("        - 연관 Entity 접근 시 NullPointer 가능성을 확인하세요.");
                break;

            case MODIFY_FK_REFERENCE:
                System.out.println("        - DB FK 참조 대상과 JPA Entity 관계가 일치하는지 확인하세요.");
                System.out.println("        - @JoinColumn 및 연관 Entity 타입을 수정하세요.");
                System.out.println("        - 기존 JOIN 쿼리 결과가 변경될 수 있으므로 검토하세요.");
                break;

            case ADD_FOREIGN_KEY:
                System.out.println("        - INSERT/UPDATE 시 FK 제약 위반 가능성을 확인하세요.");
                System.out.println("        - 저장 전 참조 Entity 존재 여부를 검증하세요.");
                System.out.println("        - 테스트 데이터 및 더미 데이터 무결성을 확인하세요.");
                break;

            default:
                System.out.println("        - 영향 경로에 포함된 코드들을 검토하세요.");
                break;
        }
    }

    private String findFirstPathValue(List<String> path, String... prefixes) {
        for (String prefix : prefixes) {
            String value = findPathValue(path, prefix);

            if (value != null) {
                return value;
            }
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

        if (idx == -1) {
            return item;
        }

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
            System.out.println(RED + "  ⚠️  HIGH 위험 항목이 감지되었습니다. 병합 전 검토가 필요합니다." + RESET);
            System.out.println();
        }
    }
}