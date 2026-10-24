package com.qualiscan.service;

import com.qualiscan.domain.Alert;
import com.qualiscan.domain.AlertStatus;
import com.qualiscan.domain.AlertType;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.repo.AlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Service managing quality alert evaluation, lifecycle (acknowledgement/resolution), and querying.
 */
@Service
@Transactional
public class AlertService {

    private final AlertRepository alertRepository;
    private final StatsService statsService;
    private final RealtimePublisher realtimePublisher;
    private final int minSample;

    public AlertService(
            AlertRepository alertRepository,
            StatsService statsService,
            RealtimePublisher realtimePublisher,
            @Value("${qualiscan.alerts.min-sample:20}") int minSample) {
        this.alertRepository = alertRepository;
        this.statsService = statsService;
        this.realtimePublisher = realtimePublisher;
        this.minSample = minSample;
    }

    /**
     * Evaluates alert conditions for the line overall and for all active product types in the window.
     */
    public void evaluate(ProductionLine line) {
        evaluate(line, null);
    }

    /**
     * Evaluates alert conditions for the line overall and for the given productType.
     * Reuses StatsService to compute defect rates over the line's alertWindowMinutes.
     */
    public void evaluate(ProductionLine line, String productType) {
        int windowMinutes = line.getAlertWindowMinutes();
        Map<String, Object> liveStats = statsService.getLiveStats(line.getId(), windowMinutes);

        // 1. Evaluate line overall
        long pass = ((Number) liveStats.getOrDefault("pass", 0L)).longValue();
        long reject = ((Number) liveStats.getOrDefault("reject", 0L)).longValue();
        long overallSample = pass + reject;

        if (overallSample >= minSample) {
            double overallRate = (double) reject / overallSample;
            checkAlertCondition(line, null, overallSample, overallRate);
        }

        // 2. Evaluate per-product defect rates
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) liveStats.get("perProductType");
        if (products != null) {
            for (Map<String, Object> prod : products) {
                String pType = (String) prod.get("productType");
                if (pType == null) {
                    continue;
                }
                // If a specific productType was passed, only evaluate that one (or evaluate all if none was passed)
                if (productType != null && !productType.equals(pType)) {
                    continue;
                }

                long pPass = ((Number) prod.getOrDefault("pass", 0L)).longValue();
                long pReject = ((Number) prod.getOrDefault("reject", 0L)).longValue();
                long pSample = pPass + pReject;

                if (pSample >= minSample) {
                    double pRate = (double) pReject / pSample;
                    checkAlertCondition(line, pType, pSample, pRate);
                }
            }
        }
    }

    private void checkAlertCondition(ProductionLine line, String targetProductType, long sampleSize, double rate) {
        List<AlertStatus> activeStatuses = List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED);
        List<Alert> activeAlerts = alertRepository.findActiveAlerts(line.getId(), targetProductType, activeStatuses);

        double threshold = line.getDefectRateThreshold();
        if (rate > threshold) {
            if (activeAlerts.isEmpty()) {
                String label = targetProductType == null ? "overall line" : targetProductType;
                String message = String.format("High defect rate for %s on %s: %.1f%% (threshold: %.1f%%)",
                        label, line.getName(), rate * 100, threshold * 100);

                Alert newAlert = new Alert(
                        line,
                        targetProductType,
                        AlertType.HIGH_DEFECT_RATE,
                        rate,
                        threshold,
                        line.getAlertWindowMinutes(),
                        (int) sampleSize,
                        message
                );
                Alert saved = alertRepository.save(newAlert);
                realtimePublisher.publishAlert(saved);
            }
        } else if (rate < threshold * 0.8) {
            for (Alert alert : activeAlerts) {
                alert.resolve();
                Alert saved = alertRepository.save(alert);
                realtimePublisher.publishAlert(saved);
            }
        }
    }

    /**
     * Acknowledges an existing alert.
     */
    public Alert acknowledge(Long id, String username) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));

        alert.acknowledge(username);
        Alert saved = alertRepository.save(alert);
        realtimePublisher.publishAlert(saved);
        return saved;
    }

    /**
     * Resolves an existing alert.
     */
    public Alert resolve(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));

        alert.resolve();
        Alert saved = alertRepository.save(alert);
        realtimePublisher.publishAlert(saved);
        return saved;
    }

    /**
     * Searches alerts with optional lineId and status filters.
     */
    @Transactional(readOnly = true)
    public Page<Alert> searchAlerts(Long lineId, AlertStatus status, Pageable pageable) {
        return alertRepository.search(lineId, status, pageable);
    }

    /**
     * Gets alert by ID or throws 404 NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public Alert getAlertById(Long id) {
        return alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
    }
}
