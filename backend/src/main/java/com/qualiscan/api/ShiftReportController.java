package com.qualiscan.api;

import com.qualiscan.domain.ShiftSummary;
import com.qualiscan.service.ShiftReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Controller for generating and retrieving manufacturing shift summaries.
 */
@RestController
@RequestMapping("/api/reports")
public class ShiftReportController {

    private final ShiftReportService shiftReportService;

    public ShiftReportController(ShiftReportService shiftReportService) {
        this.shiftReportService = shiftReportService;
    }

    public record ShiftSummaryResponse(
            Long id,
            Long lineId,
            String shiftName,
            Instant periodStart,
            Instant periodEnd,
            long total,
            long pass,
            long reject,
            long unknown,
            double defectRate,
            String byProductJson,
            String defectsByTypeJson,
            Instant createdAt,
            boolean partial
    ) {}

    @PostMapping("/shift")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ShiftSummaryResponse generateShiftReport(
            @RequestParam @NotNull Long lineId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        ShiftSummary summary = shiftReportService.generate(lineId, from, to);
        return toDto(summary);
    }

    @GetMapping("/shifts")
    public Page<ShiftSummaryResponse> getShiftReports(
            @RequestParam(required = false) Long lineId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return shiftReportService.getSummaries(
                lineId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodStart"))
        ).map(this::toDto);
    }

    private ShiftSummaryResponse toDto(ShiftSummary s) {
        return new ShiftSummaryResponse(
                s.getId(),
                s.getLine().getId(),
                s.getShiftName(),
                s.getPeriodStart(),
                s.getPeriodEnd(),
                s.getTotal(),
                s.getPass(),
                s.getReject(),
                s.getUnknown(),
                s.getDefectRate(),
                s.getByProductJson(),
                s.getDefectsByTypeJson(),
                s.getCreatedAt(),
                s.isPartial()
        );
    }
}
