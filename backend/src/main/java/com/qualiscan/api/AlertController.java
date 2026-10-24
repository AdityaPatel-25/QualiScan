package com.qualiscan.api;

import com.qualiscan.domain.Alert;
import com.qualiscan.domain.AlertStatus;
import com.qualiscan.service.AlertService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller for querying and managing quality alerts.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    public record AlertResponse(
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

    @GetMapping
    public Page<AlertResponse> listAlerts(
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return alertService.searchAlerts(
                lineId,
                status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).map(this::toDto);
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public AlertResponse acknowledge(@PathVariable Long id, Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        Alert alert = alertService.acknowledge(id, username);
        return toDto(alert);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public AlertResponse resolve(@PathVariable Long id) {
        Alert alert = alertService.resolve(id);
        return toDto(alert);
    }

    private AlertResponse toDto(Alert a) {
        return new AlertResponse(
                a.getId(),
                a.getLine().getId(),
                a.getProductType(),
                a.getType().name(),
                a.getDefectRate(),
                a.getThreshold(),
                a.getWindowMinutes(),
                a.getSampleSize(),
                a.getMessage(),
                a.getStatus().name(),
                a.getCreatedAt(),
                a.getAcknowledgedBy(),
                a.getAcknowledgedAt(),
                a.getResolvedAt()
        );
    }
}
