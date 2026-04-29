package schemaguard.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ImpactAnalyzer가 생성하는 최종 분석 결과.
 */
public class ImpactResult {

    private final List<AffectedApi> affectedApis;

    public ImpactResult(List<AffectedApi> affectedApis) {
        this.affectedApis = affectedApis;
    }

    public List<AffectedApi> getAffectedApis() { return affectedApis; }

    public List<AffectedApi> getByRisk(RiskLevel level) {
        return affectedApis.stream()
                .filter(a -> a.getRiskLevel() == level)
                .collect(Collectors.toList());
    }

    public int countByRisk(RiskLevel level) {
        return (int) affectedApis.stream()
                .filter(a -> a.getRiskLevel() == level)
                .count();
    }

    public boolean hasHighRisk() {
        return affectedApis.stream().anyMatch(a -> a.getRiskLevel() == RiskLevel.HIGH);
    }

    public boolean isEmpty() { return affectedApis.isEmpty(); }
}