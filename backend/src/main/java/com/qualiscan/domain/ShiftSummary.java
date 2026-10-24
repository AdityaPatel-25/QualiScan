package com.qualiscan.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity representing an aggregated manufacturing quality shift summary.
 */
@Entity
@Table(
        name = "shift_summaries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shift_summary_line_period",
                columnNames = {"line_id", "period_start", "period_end"}
        ),
        indexes = {
                @Index(name = "idx_shift_summary_line_start", columnList = "line_id, period_start"),
                @Index(name = "idx_shift_summary_created_at", columnList = "created_at")
        }
)
public class ShiftSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ProductionLine line;

    @Column(nullable = false)
    private String shiftName;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(nullable = false)
    private long total;

    @Column(nullable = false)
    private long pass;

    @Column(nullable = false)
    private long reject;

    @Column(name = "unknown_count", nullable = false)
    private long unknown;

    @Column(nullable = false)
    private double defectRate;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String byProductJson;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String defectsByTypeJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Transient
    private boolean partial = false;

    protected ShiftSummary() {}

    public ShiftSummary(
            ProductionLine line,
            String shiftName,
            Instant periodStart,
            Instant periodEnd,
            long total,
            long pass,
            long reject,
            long unknown,
            double defectRate,
            String byProductJson,
            String defectsByTypeJson
    ) {
        this.line = line;
        this.shiftName = shiftName;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.total = total;
        this.pass = pass;
        this.reject = reject;
        this.unknown = unknown;
        this.defectRate = defectRate;
        this.byProductJson = byProductJson;
        this.defectsByTypeJson = defectsByTypeJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public ProductionLine getLine() { return line; }
    public String getShiftName() { return shiftName; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public long getTotal() { return total; }
    public long getPass() { return pass; }
    public long getReject() { return reject; }
    public long getUnknown() { return unknown; }
    public double getDefectRate() { return defectRate; }
    public String getByProductJson() { return byProductJson; }
    public String getDefectsByTypeJson() { return defectsByTypeJson; }
    public Instant getCreatedAt() { return createdAt; }

    public void setLine(ProductionLine line) { this.line = line; }
    public void setShiftName(String shiftName) { this.shiftName = shiftName; }
    public void setPeriodStart(Instant periodStart) { this.periodStart = periodStart; }
    public void setPeriodEnd(Instant periodEnd) { this.periodEnd = periodEnd; }
    public void setTotal(long total) { this.total = total; }
    public void setPass(long pass) { this.pass = pass; }
    public void setReject(long reject) { this.reject = reject; }
    public void setUnknown(long unknown) { this.unknown = unknown; }
    public void setDefectRate(double defectRate) { this.defectRate = defectRate; }
    public void setByProductJson(String byProductJson) { this.byProductJson = byProductJson; }
    public void setDefectsByTypeJson(String defectsByTypeJson) { this.defectsByTypeJson = defectsByTypeJson; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isPartial() { return partial; }
    public void setPartial(boolean partial) { this.partial = partial; }
}
