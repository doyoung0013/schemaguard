package schemaguard.report;

import schemaguard.model.AffectedApi;
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

        if (result.isEmpty()) {
            System.out.println(GREEN + "  ✅ No affected APIs detected." + RESET);
            printFooter(result);
            return;
        }

        // 위험도 순서대로 출력: HIGH → MEDIUM → LOW
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

    private void printSection(String title, List<AffectedApi> apis, String color) {
        if (apis.isEmpty()) return;

        System.out.println(color + BOLD + title + RESET);
        System.out.println();

        for (AffectedApi api : apis) {
            System.out.println(color + "  ❌ " + api.getApiId() + RESET);
            System.out.println("     Cause  : " + api.getCause());
            System.out.println("     Risk   : " + api.getRiskLevel());
            System.out.println("     Path   :");
            printPath(api.getImpactPath());
            System.out.println();
        }
    }

    private void printPath(List<String> path) {
        for (int i = 0; i < path.size(); i++) {
            String indent = "       " + "  ".repeat(i);
            String arrow  = i == 0 ? "→ " : "→ ";
            System.out.println(indent + arrow + path.get(i));
        }
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

        // CI/CD 연동용 종료 코드 힌트
        if (result.hasHighRisk()) {
            System.out.println(RED + "  ⚠️  HIGH risk items detected. Review before merging." + RESET);
            System.out.println();
        }
    }
}
