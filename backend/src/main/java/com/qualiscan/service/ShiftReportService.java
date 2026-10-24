package com.qualiscan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.domain.ShiftSummary;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.InspectionSpecifications;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.repo.ShiftSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for generating, scheduling, and querying manufacturing shift summary reports.
 */
@Service
@Transactional
public class ShiftReportService {

    private static final Logger log = LoggerFactory.getLogger(ShiftReportService.class);

    private final ShiftSummaryRepository shiftSummaryRepository;
    private final ProductionLineRepository lineRepository;
    private final InspectionRepository inspectionRepository;
    private final ObjectMapper objectMapper;
    private final String shiftZone;

    public ShiftReportService(
            ShiftSummaryRepository shiftSummaryRepository,
            ProductionLineRepository lineRepository,
            InspectionRepository inspectionRepository,
            ObjectMapper objectMapper,
            @Value("${qualiscan.shifts.zone:Asia/Kolkata}") String shiftZone) {
        this.shiftSummaryRepository = shiftSummaryRepository;
        this.lineRepository = lineRepository;
        this.inspectionRepository = inspectionRepository;
        this.objectMapper = objectMapper;
        this.shiftZone = shiftZone;
    }

    /**
     * Generates a shift summary for the given line and time range.
     * Persisted and idempotent only when 'to' is in the past.
     * Returns a computed, non-persisted summary with partial = true if 'to' is in the future.
     */
    public ShiftSummary generate(Long lineId, Instant from, Instant to) {
        ProductionLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        Instant now = Instant.now();
        boolean isFuture = to.isAfter(now);

        // 3) Persist and reuse (idempotent) only when 'to' is in the past.
        if (!isFuture) {
            Optional<ShiftSummary> existing = shiftSummaryRepository
                    .findByLineIdAndPeriodStartAndPeriodEnd(lineId, from, to);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Shared dynamic filter: ensures search and reports cannot disagree
        Specification<InspectionResult> spec = InspectionSpecifications.filter(lineId, null, null, from, to);

        // Aggregate overall verdicts directly in database
        Map<String, Long> verdicts = new HashMap<>();
        inspectionRepository.aggregateVerdicts(spec)
                .forEach(x -> verdicts.put(x[0].toString(), ((Number) x[1]).longValue()));

        long pass = verdicts.getOrDefault("PASS", 0L);
        long reject = verdicts.getOrDefault("REJECT", 0L);
        long unknown = verdicts.getOrDefault("UNKNOWN", 0L);
        long total = pass + reject + unknown;
        double defectRate = StatsService.calculateDefectRate(pass, reject);

        // Aggregate per-product verdicts directly in database
        Map<String, Map<String, Long>> pmap = new TreeMap<>();
        inspectionRepository.aggregateTypeVerdicts(spec)
                .forEach(x -> pmap.computeIfAbsent(x[0].toString(), k -> new HashMap<>())
                        .put(x[1].toString(), ((Number) x[2]).longValue()));

        Map<String, Object> byProduct = new TreeMap<>();
        pmap.forEach((productType, v) -> {
            long pPass = v.getOrDefault("PASS", 0L);
            long pReject = v.getOrDefault("REJECT", 0L);
            long pUnknown = v.getOrDefault("UNKNOWN", 0L);
            long pTotal = pPass + pReject + pUnknown;
            double pRate = StatsService.calculateDefectRate(pPass, pReject);

            Map<String, Object> pStats = new LinkedHashMap<>();
            pStats.put("total", pTotal);
            pStats.put("pass", pPass);
            pStats.put("reject", pReject);
            pStats.put("unknown", pUnknown);
            pStats.put("defectRate", pRate);
            byProduct.put(productType, pStats);
        });

        // Aggregate defect types breakdown directly from rejected inspections in database
        Map<String, Long> defectsByType = new TreeMap<>();
        for (String s : inspectionRepository.findFailedChecks(spec)) {
            for (String c : s.split(",")) {
                if (!c.isBlank()) {
                    defectsByType.merge(c.trim(), 1L, Long::sum);
                }
            }
        }

        String byProductJson;
        String defectsByTypeJson;
        try {
            byProductJson = objectMapper.writeValueAsString(byProduct);
            defectsByTypeJson = objectMapper.writeValueAsString(defectsByType);
        } catch (JsonProcessingException e) {
            byProductJson = "{}";
            defectsByTypeJson = "{}";
        }

        String shiftName = determineShiftName(from, to);

        ShiftSummary summary = new ShiftSummary(
                line,
                shiftName,
                from,
                to,
                total,
                pass,
                reject,
                unknown,
                defectRate,
                byProductJson,
                defectsByTypeJson
        );

        if (isFuture) {
            summary.setPartial(true);
            return summary;
        }

        return shiftSummaryRepository.save(summary);
    }

    /**
     * Scheduled job running at 06:00, 14:00, and 22:00 in the configured time zone
     * to generate summaries for the shift that just ended.
     */
    @Scheduled(cron = "0 0 6,14,22 * * *", zone = "${qualiscan.shifts.zone:Asia/Kolkata}")
    public void generateScheduledShiftSummaries() {
        ZoneId zone = ZoneId.of(shiftZone);
        ZonedDateTime now = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime start = now.minusHours(8);

        Instant from = start.toInstant();
        Instant to = now.toInstant();

        // Guard: ensure the shift has already ended
        if (to.isAfter(Instant.now())) {
            log.warn("Skipping scheduled shift report generation because periodEnd {} is in the future", to);
            return;
        }

        log.info("Running scheduled shift report generation for period {} to {}", from, to);
        for (ProductionLine line : lineRepository.findAll()) {
            try {
                generate(line.getId(), from, to);
            } catch (Exception e) {
                log.error("Failed to generate shift summary for line {}: {}", line.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Searches shift summaries with optional line filter and pagination.
     */
    @Transactional(readOnly = true)
    public Page<ShiftSummary> getSummaries(Long lineId, Pageable pageable) {
        return shiftSummaryRepository.search(lineId, pageable);
    }

    /**
     * Naming: known shifts A, B, and C by their configured windows;
     * any other range is "Custom <from date> to <to date>" in the configured shift zone.
     */
    public String determineShiftName(Instant from, Instant to) {
        ZoneId zone = ZoneId.of(shiftZone);
        ZonedDateTime fromZdt = from.atZone(zone);
        ZonedDateTime toZdt = to.atZone(zone);

        long minutes = Duration.between(from, to).toMinutes();
        if (minutes == 8 * 60 && fromZdt.getMinute() == 0 && toZdt.getMinute() == 0 && fromZdt.getSecond() == 0 && toZdt.getSecond() == 0) {
            int startHour = fromZdt.getHour();
            int endHour = toZdt.getHour();
            if (startHour == 6 && endHour == 14) {
                return "Shift A";
            }
            if (startHour == 14 && endHour == 22) {
                return "Shift B";
            }
            if (startHour == 22 && endHour == 6) {
                return "Shift C";
            }
        }
        return "Custom " + fromZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " to " + toZdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
