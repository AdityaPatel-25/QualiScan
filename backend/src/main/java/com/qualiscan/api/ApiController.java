package com.qualiscan.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.Batch;
import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.ProductProfile;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.domain.Verdict;
import com.qualiscan.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller exposing REST endpoints for authentication, production lines,
 * product profiles, batches, inspections, snapshots, and quality statistics.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final AuthService authService;
    private final ProductionLineService lineService;
    private final ProfileService profileService;
    private final BatchService batchService;
    private final InspectionService inspectionService;
    private final StatsService statsService;
    private final ObjectMapper mapper;

    public ApiController(
            AuthService authService,
            ProductionLineService lineService,
            ProfileService profileService,
            BatchService batchService,
            InspectionService inspectionService,
            StatsService statsService,
            ObjectMapper mapper) {
        this.authService = authService;
        this.lineService = lineService;
        this.profileService = profileService;
        this.batchService = batchService;
        this.inspectionService = inspectionService;
        this.statsService = statsService;
        this.mapper = mapper;
    }

    // Request & Response DTO records
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String token, String role, Instant expiresAt) {}
    public record SettingsRequest(
            @DecimalMin("0.0") @DecimalMax("1.0") double defectRateThreshold,
            @Min(1) @Max(1440) int alertWindowMinutes) {}
    public record ProfileRequest(@NotBlank String rulesJson) {}
    public record ProfileResponse(String productType, String rulesJson, Instant updatedAt) {}
    public record BatchRequest(@NotNull Long lineId, @NotBlank String name) {}
    public record InspectionRequest(
            @NotBlank String trackId,
            @NotBlank String productType,
            @DecimalMin("0") @DecimalMax("1") double confidence,
            @NotNull Verdict verdict,
            List<String> failedChecks,
            @PositiveOrZero double area,
            @DecimalMin("0") @DecimalMax("1") double circularity,
            @PositiveOrZero double aspectRatio,
            @NotNull Instant inspectedAt) {}

    // Authentication endpoints
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AuthService.LoginResult result = authService.login(req.username(), req.password());
        return new LoginResponse(result.token(), result.role(), result.expiresAt());
    }

    // Production Line endpoints
    @GetMapping("/lines")
    public List<Map<String, Object>> allLines() {
        return lineService.getAllLines().stream().map(this::lineDto).toList();
    }

    @GetMapping("/lines/{id}")
    public Map<String, Object> line(@PathVariable Long id) {
        return lineDto(lineService.getLineById(id));
    }

    @PutMapping("/lines/{id}/settings")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Map<String, Object> settings(@PathVariable Long id, @Valid @RequestBody SettingsRequest req) {
        ProductionLine updated = lineService.updateSettings(id, req.defectRateThreshold(), req.alertWindowMinutes());
        return lineDto(updated);
    }

    @PostMapping("/lines/{id}/rotate-key")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> rotate(@PathVariable Long id) {
        String key = lineService.rotateApiKey(id);
        return Map.of("apiKey", key);
    }

    // Product Profile endpoints
    @GetMapping("/profiles")
    public List<ProfileResponse> allProfiles() {
        return profileService.getAllProfiles().stream().map(this::profileDto).toList();
    }

    @GetMapping("/profiles/{type}")
    public ProfileResponse profile(@PathVariable String type) {
        return profileDto(profileService.getProfileByType(type));
    }

    @PutMapping("/profiles/{type}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ProfileResponse updateProfile(@PathVariable String type, @Valid @RequestBody ProfileRequest req) {
        ProductProfile updated = profileService.updateProfile(type, req.rulesJson());
        return profileDto(updated);
    }

    // Batch Management endpoints
    @PostMapping("/batches")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Map<String, Object> startBatch(@Valid @RequestBody BatchRequest req) {
        Batch batch = batchService.startBatch(req.lineId(), req.name());
        return batchDto(batch);
    }

    @PostMapping("/batches/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Map<String, Object> closeBatch(@PathVariable Long id) {
        Batch closed = batchService.closeBatch(id);
        return batchDto(closed);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> listBatches(@RequestParam(required = false) Long lineId) {
        return batchService.getBatches(lineId).stream().map(this::batchDto).toList();
    }

    // Inspection endpoints
    @PostMapping(value = "/inspections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createInspection(
            @RequestPart("result") String result,
            @RequestPart(value = "snapshot", required = false) MultipartFile snapshot,
            Authentication auth) {
        try {
            InspectionRequest req = mapper.readValue(result, InspectionRequest.class);
            if (req.trackId() == null || req.productType() == null || req.verdict() == null || req.inspectedAt() == null) {
                throw new IllegalArgumentException("Missing required result fields");
            }
            Long lineId = Long.valueOf(auth.getName().substring("device:".length()));
            InspectionService.InspectionData data = new InspectionService.InspectionData(
                    req.trackId(),
                    req.productType(),
                    req.confidence(),
                    req.verdict(),
                    req.failedChecks(),
                    req.area(),
                    req.circularity(),
                    req.aspectRatio(),
                    req.inspectedAt()
            );
            InspectionResult saved = inspectionService.recordInspection(lineId, data, snapshot);
            return Map.of("id", saved.getId());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid inspection result: " + e.getMessage());
        }
    }

    @GetMapping("/inspections")
    public Page<Map<String, Object>> search(
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) Verdict verdict,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inspectionService.searchInspections(
                lineId,
                productType,
                verdict,
                from,
                to,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "inspectedAt"))
        ).map(this::inspectionDto);
    }

    @GetMapping("/snapshots/{id}")
    public ResponseEntity<Resource> snapshot(@PathVariable Long id) {
        Resource resource = inspectionService.loadSnapshot(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(resource);
    }

    // Statistics endpoints
    @GetMapping("/stats/live")
    public Map<String, Object> live(
            @RequestParam(required = false) Long lineId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(1440) int minutes) {
        return statsService.getLiveStats(lineId, minutes);
    }

    @GetMapping("/stats/batch/{batchId}")
    public Map<String, Object> batchStats(@PathVariable Long batchId) {
        return statsService.getBatchStats(batchId);
    }

    @GetMapping("/stats/hourly")
    public List<Map<String, Object>> hourly(
            @RequestParam Long lineId,
            @RequestParam LocalDate date) {
        return statsService.getHourlyStats(lineId, date);
    }

    // Helper DTO mappers
    private ProfileResponse profileDto(ProductProfile p) {
        return new ProfileResponse(p.getProductType(), p.getRulesJson(), p.getUpdatedAt());
    }

    private Map<String, Object> lineDto(ProductionLine l) {
        return Map.of(
                "id", l.getId(),
                "name", l.getName(),
                "activeBatchId", l.getActiveBatchId() == null ? "" : l.getActiveBatchId(),
                "defectRateThreshold", l.getDefectRateThreshold(),
                "alertWindowMinutes", l.getAlertWindowMinutes()
        );
    }

    private Map<String, Object> batchDto(Batch b) {
        return Map.of(
                "id", b.getId(),
                "lineId", b.getLine().getId(),
                "name", b.getName(),
                "startedAt", b.getStartedAt(),
                "endedAt", b.getEndedAt() == null ? "" : b.getEndedAt(),
                "status", b.getStatus()
        );
    }

    private Map<String, Object> inspectionDto(InspectionResult i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("lineId", i.getLine().getId());
        m.put("batchId", i.getBatch() == null ? null : i.getBatch().getId());
        m.put("trackId", i.getTrackId());
        m.put("productType", i.getProductType());
        m.put("confidence", i.getIdentificationConfidence());
        m.put("verdict", i.getVerdict());
        m.put("failedChecks", i.getFailedChecks());
        m.put("area", i.getArea());
        m.put("circularity", i.getCircularity());
        m.put("aspectRatio", i.getAspectRatio());
        m.put("inspectedAt", i.getInspectedAt());
        m.put("receivedAt", i.getReceivedAt());
        return m;
    }
}
