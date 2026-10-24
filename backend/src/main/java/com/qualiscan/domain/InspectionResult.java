package com.qualiscan.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="inspection_results",indexes={@Index(columnList="line_id,inspectedAt"),@Index(columnList="productType,verdict"),@Index(columnList="batch_id")})
public class InspectionResult {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private ProductionLine line;
 @ManyToOne private Batch batch;
 @Column(nullable=false) private String trackId;
 @Column(nullable=false) private String productType;
 @Column(nullable=false) private double identificationConfidence;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private Verdict verdict;
 @Column(length=2000) private String failedChecks;
 private double area,circularity,aspectRatio;
 private String snapshotPath;
 @Column(nullable=false) private Instant inspectedAt;
 @Column(nullable=false) private Instant receivedAt=Instant.now();
 protected InspectionResult(){}
 public InspectionResult(ProductionLine line,Batch batch,String trackId,String productType,double confidence,Verdict verdict,String failedChecks,double area,double circularity,double aspectRatio,String snapshotPath,Instant inspectedAt){this.line=line;this.batch=batch;this.trackId=trackId;this.productType=productType;this.identificationConfidence=confidence;this.verdict=verdict;this.failedChecks=failedChecks;this.area=area;this.circularity=circularity;this.aspectRatio=aspectRatio;this.snapshotPath=snapshotPath;this.inspectedAt=inspectedAt;}
 public Long getId(){return id;} public ProductionLine getLine(){return line;} public Batch getBatch(){return batch;} public String getTrackId(){return trackId;} public String getProductType(){return productType;} public double getIdentificationConfidence(){return identificationConfidence;} public Verdict getVerdict(){return verdict;} public String getFailedChecks(){return failedChecks;} public double getArea(){return area;} public double getCircularity(){return circularity;} public double getAspectRatio(){return aspectRatio;} public String getSnapshotPath(){return snapshotPath;} public Instant getInspectedAt(){return inspectedAt;} public Instant getReceivedAt(){return receivedAt;}
}
