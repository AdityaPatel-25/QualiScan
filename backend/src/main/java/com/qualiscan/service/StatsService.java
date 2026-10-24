package com.qualiscan.service;

import com.qualiscan.repo.BatchRepository;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Service for calculating defect rates, live conveyor metrics, batch statistics, and hourly breakdowns.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    private final InspectionRepository inspectionRepository;
    private final BatchRepository batchRepository;
    private final ProductionLineRepository lineRepository;

    public StatsService(
            InspectionRepository inspectionRepository,
            BatchRepository batchRepository,
            ProductionLineRepository lineRepository) {
        this.inspectionRepository = inspectionRepository;
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
    }

    /**
     * Calculates the defect rate as reject / (pass + reject).
     * If no valid evaluated items exist, returns 0.0.
     */
    public static double calculateDefectRate(long pass, long reject) {
        if (pass + reject == 0) {
            return 0.0;
        }
        return (double) reject / (pass + reject);
    }

    /**
     * Computes live statistics over a rolling window (minutes), optionally filtered by line.
     */
    public Map<String, Object> getLiveStats(Long lineId, int minutes) {
        Instant start = Instant.now().minusSeconds(minutes * 60L);

        Map<String, Long> byVerdict = new HashMap<>();
        inspectionRepository.verdictSince(lineId, start)
                .forEach(x -> byVerdict.put(x[0].toString(), ((Number) x[1]).longValue()));

        List<Map<String, Object>> products = new ArrayList<>();
        Map<String, Map<String, Long>> pmap = new TreeMap<>();
        inspectionRepository.typeVerdictSince(lineId, start)
                .forEach(x -> pmap.computeIfAbsent(x[0].toString(), k -> new HashMap<>())
                        .put(x[1].toString(), ((Number) x[2]).longValue()));

        pmap.forEach((productType, verdictMap) -> products.add(buildCountsMap(productType, verdictMap, null)));

        return buildCountsMap(null, byVerdict, products);
    }

    /**
     * Computes statistics for a specific batch, including defect rate, defect breakdown, and counts per product type.
     */
    public Map<String, Object> getBatchStats(Long batchId) {
        if (!batchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found");
        }

        Map<String, Long> verdicts = new HashMap<>();
        inspectionRepository.verdictBatch(batchId)
                .forEach(x -> verdicts.put(x[0].toString(), ((Number) x[1]).longValue()));

        Map<String, Long> defectsByType = new TreeMap<>();
        for (String failedList : inspectionRepository.failedBatch(batchId)) {
            for (String defect : failedList.split(",")) {
                if (!defect.isBlank()) {
                    defectsByType.merge(defect.trim(), 1L, Long::sum);
                }
            }
        }

        Map<String, Long> countsPerProduct = new TreeMap<>();
        inspectionRepository.typeBatch(batchId)
                .forEach(x -> countsPerProduct.put(x[0].toString(), ((Number) x[1]).longValue()));

        Map<String, Object> output = buildCountsMap(null, verdicts, null);
        output.put("defectsByType", defectsByType);
        output.put("countsPerProductType", countsPerProduct);
        return output;
    }

    /**
     * Computes 24-hour pass/reject counts for a line on a given calendar date.
     */
    public List<Map<String, Object>> getHourlyStats(Long lineId, LocalDate date) {
        if (!lineRepository.existsById(lineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found");
        }

        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        Map<Integer, Map<String, Long>> hourlyData = new TreeMap<>();
        for (int h = 0; h < 24; h++) {
            hourlyData.put(h, new HashMap<>());
        }

        inspectionRepository.hourly(lineId, start, end)
                .forEach(x -> hourlyData.get(((Number) x[0]).intValue())
                        .put(x[1].toString(), ((Number) x[2]).longValue()));

        return hourlyData.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", e.getKey());
            m.put("pass", e.getValue().getOrDefault("PASS", 0L));
            m.put("reject", e.getValue().getOrDefault("REJECT", 0L));
            return m;
        }).toList();
    }

    /**
     * Builds summary count map including total and calculated defect rate.
     */
    private Map<String, Object> buildCountsMap(String productType, Map<String, Long> verdictMap, List<Map<String, Object>> products) {
        long pass = verdictMap.getOrDefault("PASS", 0L);
        long reject = verdictMap.getOrDefault("REJECT", 0L);
        long unknown = verdictMap.getOrDefault("UNKNOWN", 0L);
        long total = pass + reject + unknown;
        double defectRate = calculateDefectRate(pass, reject);

        Map<String, Object> m = new LinkedHashMap<>();
        if (productType != null) {
            m.put("productType", productType);
        }
        m.put("total", total);
        m.put("pass", pass);
        m.put("reject", reject);
        m.put("unknown", unknown);
        m.put("defectRate", defectRate);
        if (products != null) {
            m.put("perProductType", products);
        }
        return m;
    }
}
