package com.qualiscan.service;

import com.qualiscan.domain.Alert;
import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.repo.ProductionLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service for publishing realtime STOMP WebSocket messages to subscribed clients.
 */
@Service
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final StatsService statsService;
    private final ProductionLineRepository lineRepository;

    public RealtimePublisher(
            SimpMessagingTemplate messagingTemplate,
            StatsService statsService,
            ProductionLineRepository lineRepository) {
        this.messagingTemplate = messagingTemplate;
        this.statsService = statsService;
        this.lineRepository = lineRepository;
    }

    public record InspectionMessage(
            Long id,
            String trackId,
            String productType,
            String verdict,
            List<String> failedChecks,
            double confidence,
            Instant inspectedAt,
            String snapshotUrl
    ) {}

    public record AlertMessage(
            Long id,
            Long lineId,
            String productType,
            String type,
            double defectRate,
            double threshold,
            int windowMinutes,
            int sampleSize,
            String message,
            String status,
            Instant createdAt,
            String acknowledgedBy,
            Instant acknowledgedAt,
            Instant resolvedAt
    ) {}

    /**
     * Publishes inspection result to /topic/lines/{lineId}/inspections
     */
    public void publishInspection(InspectionResult result) {
        try {
            Long lineId = result.getLine().getId();
            String destination = "/topic/lines/" + lineId + "/inspections";

            List<String> failedChecksList = (result.getFailedChecks() == null || result.getFailedChecks().isBlank())
                    ? List.of()
                    : Arrays.stream(result.getFailedChecks().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

            String snapshotUrl = result.getSnapshotPath() != null
                    ? "/api/snapshots/" + result.getId()
                    : null;

            InspectionMessage message = new InspectionMessage(
                    result.getId(),
                    result.getTrackId(),
                    result.getProductType(),
                    result.getVerdict().name(),
                    failedChecksList,
                    result.getIdentificationConfidence(),
                    result.getInspectedAt(),
                    snapshotUrl
            );

            messagingTemplate.convertAndSend(destination, message);
        } catch (Exception e) {
            log.error("Failed to publish inspection WebSocket message: {}", e.getMessage(), e);
        }
    }

    /**
     * Publishes live conveyor stats to /topic/lines/{lineId}/stats
     */
    public void publishStats(Long lineId) {
        try {
            ProductionLine line = lineRepository.findById(lineId).orElse(null);
            if (line == null) {
                return;
            }

            int windowMinutes = line.getAlertWindowMinutes();
            Map<String, Object> liveStats = statsService.getLiveStats(lineId, windowMinutes);
            String destination = "/topic/lines/" + lineId + "/stats";

            messagingTemplate.convertAndSend(destination, liveStats);
        } catch (Exception e) {
            log.error("Failed to publish stats WebSocket message: {}", e.getMessage(), e);
        }
    }

    /**
     * Publishes alert to /topic/alerts
     */
    public void publishAlert(Alert alert) {
        try {
            AlertMessage message = new AlertMessage(
                    alert.getId(),
                    alert.getLine().getId(),
                    alert.getProductType(),
                    alert.getType().name(),
                    alert.getDefectRate(),
                    alert.getThreshold(),
                    alert.getWindowMinutes(),
                    alert.getSampleSize(),
                    alert.getMessage(),
                    alert.getStatus().name(),
                    alert.getCreatedAt(),
                    alert.getAcknowledgedBy(),
                    alert.getAcknowledgedAt(),
                    alert.getResolvedAt()
            );

            messagingTemplate.convertAndSend("/topic/alerts", message);
        } catch (Exception e) {
            log.error("Failed to publish alert WebSocket message: {}", e.getMessage(), e);
        }
    }
}
