package com.qualiscan.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entity representing a quality alert raised on a production line or product type.
 */
@Entity
@Table(name = "alerts", indexes = {
        @Index(columnList = "line_id,status"),
        @Index(columnList = "createdAt")
})
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private ProductionLine line;

    private String productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertType type = AlertType.HIGH_DEFECT_RATE;

    @Column(nullable = false)
    private double defectRate;

    @Column(nullable = false)
    private double threshold;

    @Column(nullable = false)
    private int windowMinutes;

    @Column(nullable = false)
    private int sampleSize;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status = AlertStatus.OPEN;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private String acknowledgedBy;

    private Instant acknowledgedAt;

    private Instant resolvedAt;

    protected Alert() {}

    public Alert(
            ProductionLine line,
            String productType,
            AlertType type,
            double defectRate,
            double threshold,
            int windowMinutes,
            int sampleSize,
            String message
    ) {
        this.line = line;
        this.productType = productType;
        this.type = type;
        this.defectRate = defectRate;
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
        this.sampleSize = sampleSize;
        this.message = message;
        this.status = AlertStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public ProductionLine getLine() { return line; }
    public String getProductType() { return productType; }
    public AlertType getType() { return type; }
    public double getDefectRate() { return defectRate; }
    public double getThreshold() { return threshold; }
    public int getWindowMinutes() { return windowMinutes; }
    public int getSampleSize() { return sampleSize; }
    public String getMessage() { return message; }
    public AlertStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    public void setLine(ProductionLine line) { this.line = line; }
    public void setProductType(String productType) { this.productType = productType; }
    public void setType(AlertType type) { this.type = type; }
    public void setDefectRate(double defectRate) { this.defectRate = defectRate; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
    public void setMessage(String message) { this.message = message; }
    public void setStatus(AlertStatus status) { this.status = status; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public void acknowledge(String user) {
        this.status = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedBy = user;
        this.acknowledgedAt = Instant.now();
    }

    public void resolve() {
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }
}
