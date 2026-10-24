package com.qualiscan;

import com.qualiscan.domain.*;
import com.qualiscan.repo.AlertRepository;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.security.Crypto;
import com.qualiscan.service.AlertService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

@SpringBootTest
class AlertServiceTest {

    @Autowired AlertService alertService;
    @Autowired AlertRepository alertRepository;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired ProductionLineRepository lineRepository;

    private ProductionLine testLine;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        inspectionRepository.deleteAll();

        String hash = Crypto.sha256("alert-test-key");
        testLine = lineRepository.findAll().stream()
                .filter(l -> "Alert Line".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lineRepository.save(new ProductionLine("Alert Line", hash)));
        testLine.setSettings(0.10, 60); // 10% defect rate threshold, 60 minutes window
        testLine = lineRepository.save(testLine);
    }

    private void addInspections(String productType, Verdict verdict, int count) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            InspectionResult res = new InspectionResult(
                    testLine,
                    null,
                    "track-" + productType + "-" + i + "-" + System.nanoTime(),
                    productType,
                    0.99,
                    verdict,
                    verdict == Verdict.REJECT ? "dent" : "",
                    100.0,
                    0.8,
                    1.1,
                    null,
                    now.minusSeconds(i)
            );
            inspectionRepository.save(res);
        }
    }

    @Test
    void belowMinSampleCreatesNoAlert() {
        // Threshold is 10%, min-sample is 20.
        // Add 10 inspections: 5 PASS, 5 REJECT (50% defect rate, but sampleSize = 10 < 20)
        addInspections("cap", Verdict.PASS, 5);
        addInspections("cap", Verdict.REJECT, 5);

        alertService.evaluate(testLine);

        List<Alert> alerts = alertRepository.findAll();
        Assertions.assertTrue(alerts.isEmpty(), "Below min-sample must not trigger an alert");
    }

    @Test
    void aboveThresholdWithEnoughSamplesCreatesExactlyOneAlertWithoutDuplicates() {
        // Line overall has enough samples (>= 20), but individual products have < 20:
        // Cap: 8 PASS, 7 REJECT (sample = 15 < 20, no per-product alert)
        // Card: 8 PASS, 7 REJECT (sample = 15 < 20, no per-product alert)
        // Overall: 16 PASS, 14 REJECT (sample = 30 >= 20, defect rate = 14 / 30 = 46.7% > 10%)
        addInspections("cap", Verdict.PASS, 8);
        addInspections("cap", Verdict.REJECT, 7);
        addInspections("card", Verdict.PASS, 8);
        addInspections("card", Verdict.REJECT, 7);

        alertService.evaluate(testLine);

        List<Alert> alerts = alertRepository.findAll();
        Assertions.assertEquals(1, alerts.size(), "Should create exactly one overall alert");
        Alert alert = alerts.get(0);
        Assertions.assertEquals(AlertStatus.OPEN, alert.getStatus());
        Assertions.assertNull(alert.getProductType(), "Overall alert has null productType");
        Assertions.assertEquals(14.0 / 30.0, alert.getDefectRate(), 0.001);

        // Add 4 more REJECT inspections and evaluate again
        addInspections("cap", Verdict.REJECT, 2);
        addInspections("card", Verdict.REJECT, 2);
        alertService.evaluate(testLine);

        List<Alert> afterMore = alertRepository.findAll();
        Assertions.assertEquals(1, afterMore.size(), "Must not create duplicate alerts while an alert is already active");
    }

    @Test
    void fallingBelowEightyPercentOfThresholdAutoResolvesAlert() {
        // Trigger overall alert: 16 PASS, 14 REJECT (rate 46.7% > 10%)
        addInspections("cap", Verdict.PASS, 8);
        addInspections("cap", Verdict.REJECT, 7);
        addInspections("card", Verdict.PASS, 8);
        addInspections("card", Verdict.REJECT, 7);
        alertService.evaluate(testLine);

        List<Alert> initialAlerts = alertRepository.findAll();
        Assertions.assertEquals(1, initialAlerts.size());
        Assertions.assertEquals(AlertStatus.OPEN, initialAlerts.get(0).getStatus());

        // Threshold is 0.10. 80% of threshold is 0.08.
        // Add 300 PASS inspections (150 cap, 150 card):
        // Total = 316 PASS, 14 REJECT. Rate = 14 / 330 = 4.24% (< 8%)
        addInspections("cap", Verdict.PASS, 150);
        addInspections("card", Verdict.PASS, 150);
        alertService.evaluate(testLine);

        List<Alert> resolvedAlerts = alertRepository.findAll();
        Assertions.assertEquals(1, resolvedAlerts.size());
        Alert resolved = resolvedAlerts.get(0);
        Assertions.assertEquals(AlertStatus.RESOLVED, resolved.getStatus(), "Alert should be auto-resolved below 80% threshold");
        Assertions.assertNotNull(resolved.getResolvedAt());
    }

    @Test
    void perProductAlertIsIndependentOfOverallAlert() {
        // Product "cap": 200 PASS, 0 REJECT (rate = 0%)
        // Product "biscuit": 15 PASS, 5 REJECT (sample = 20, rate = 5 / 20 = 25% > 10%)
        // Overall: 215 PASS, 5 REJECT (sample = 220, rate = 5 / 220 = 2.27% < 10%)
        addInspections("cap", Verdict.PASS, 200);
        addInspections("biscuit", Verdict.PASS, 15);
        addInspections("biscuit", Verdict.REJECT, 5);

        alertService.evaluate(testLine);

        // Overall alert should NOT exist
        List<Alert> overallAlerts = alertRepository.findActiveAlerts(
                testLine.getId(), null, List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
        Assertions.assertTrue(overallAlerts.isEmpty(), "Overall defect rate is 2.27% < 10%, so no overall alert");

        // Per-product alert for "biscuit" SHOULD exist
        List<Alert> biscuitAlerts = alertRepository.findActiveAlerts(
                testLine.getId(), "biscuit", List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
        Assertions.assertEquals(1, biscuitAlerts.size(), "Product 'biscuit' exceeded threshold, so biscuit alert must exist");
        Assertions.assertEquals("biscuit", biscuitAlerts.get(0).getProductType());
        Assertions.assertEquals(0.25, biscuitAlerts.get(0).getDefectRate(), 0.001);
    }
}
