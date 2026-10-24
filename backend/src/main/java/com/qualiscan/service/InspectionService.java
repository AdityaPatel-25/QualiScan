package com.qualiscan.service;

import com.qualiscan.domain.Batch;
import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.domain.Verdict;
import com.qualiscan.repo.BatchRepository;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service managing defect inspections, search queries, snapshot storage, and alert/realtime triggers.
 */
@Service
@Transactional
public class InspectionService {

    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

    private final InspectionRepository inspectionRepository;
    private final ProductionLineRepository lineRepository;
    private final BatchRepository batchRepository;
    private final AlertService alertService;
    private final RealtimePublisher realtimePublisher;
    private final Path uploadDir;

    public InspectionService(
            InspectionRepository inspectionRepository,
            ProductionLineRepository lineRepository,
            BatchRepository batchRepository,
            AlertService alertService,
            RealtimePublisher realtimePublisher,
            @Value("${qualiscan.upload-dir}") String uploadDir) {
        this.inspectionRepository = inspectionRepository;
        this.lineRepository = lineRepository;
        this.batchRepository = batchRepository;
        this.alertService = alertService;
        this.realtimePublisher = realtimePublisher;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public record InspectionData(
            String trackId,
            String productType,
            double confidence,
            Verdict verdict,
            List<String> failedChecks,
            double area,
            double circularity,
            double aspectRatio,
            Instant inspectedAt
    ) {}

    /**
     * Records a new inspection result, evaluates quality alerts, and broadcasts realtime updates.
     */
    public InspectionResult recordInspection(Long lineId, InspectionData data, MultipartFile snapshot) {
        ProductionLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        String savedPath = null;
        if (snapshot != null && !snapshot.isEmpty()) {
            savedPath = saveSnapshot(snapshot);
        }

        Batch batch = null;
        if (line.getActiveBatchId() != null) {
            batch = batchRepository.findById(line.getActiveBatchId()).orElse(null);
        }

        String failedChecksStr = data.failedChecks() == null ? "" : String.join(",", data.failedChecks());

        InspectionResult result = new InspectionResult(
                line,
                batch,
                data.trackId(),
                data.productType(),
                data.confidence(),
                data.verdict(),
                failedChecksStr,
                data.area(),
                data.circularity(),
                data.aspectRatio(),
                savedPath,
                data.inspectedAt()
        );

        InspectionResult saved = inspectionRepository.save(result);

        // 1. Evaluate alerts right after saving (catch and log so inspection POST never fails)
        try {
            alertService.evaluate(line, saved.getProductType());
        } catch (Exception e) {
            log.error("Failed to evaluate alerts after inspection {}: {}", saved.getId(), e.getMessage(), e);
        }

        // 2. Publish realtime STOMP updates (catch and log so inspection POST never fails)
        try {
            realtimePublisher.publishInspection(saved);
            realtimePublisher.publishStats(line.getId());
        } catch (Exception e) {
            log.error("Failed to publish realtime WebSocket updates after inspection {}: {}", saved.getId(), e.getMessage(), e);
        }

        return saved;
    }

    /**
     * Searches inspections with optional filtering and pagination.
     */
    @Transactional(readOnly = true)
    public Page<InspectionResult> searchInspections(
            Long lineId,
            String productType,
            Verdict verdict,
            Instant from,
            Instant to,
            Pageable pageable) {
        return inspectionRepository.findAll(
                com.qualiscan.repo.InspectionSpecifications.filter(lineId, productType, verdict, from, to),
                pageable
        );
    }

    /**
     * Loads the snapshot image resource for a given inspection ID.
     */
    @Transactional(readOnly = true)
    public Resource loadSnapshot(Long inspectionId) {
        InspectionResult inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inspection not found"));

        if (inspection.getSnapshotPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found");
        }

        Resource resource = new FileSystemResource(inspection.getSnapshotPath());
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found");
        }

        return resource;
    }

    /**
     * Helper method to validate and store a snapshot JPEG image.
     */
    private String saveSnapshot(MultipartFile snapshot) {
        if (snapshot.getSize() > 2 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot exceeds 2 MB");
        }
        if (!"image/jpeg".equalsIgnoreCase(snapshot.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snapshot must be image/jpeg");
        }

        try {
            Files.createDirectories(uploadDir);
            Path targetFile = uploadDir.resolve(UUID.randomUUID() + ".jpg").normalize();
            if (!targetFile.startsWith(uploadDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot path");
            }
            Files.copy(snapshot.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
            return targetFile.toString();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save snapshot: " + e.getMessage());
        }
    }
}
