package schemaguard.report;

import schemaguard.model.ImpactResult;

/**
 * ImpactResult 를 다양한 포맷으로 출력하기 위한 공통 인터페이스.
 */
public interface ReportGenerator {
    void generate(ImpactResult result);
}
