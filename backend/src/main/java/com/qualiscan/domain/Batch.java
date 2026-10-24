package com.qualiscan.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="batches",indexes={@Index(columnList="line_id,status"),@Index(columnList="startedAt")})
public class Batch {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private ProductionLine line;
 @Column(nullable=false) private String name;
 @Column(nullable=false) private Instant startedAt=Instant.now();
 private Instant endedAt;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private BatchStatus status=BatchStatus.OPEN;
 protected Batch(){} public Batch(ProductionLine line,String name){this.line=line;this.name=name;}
 public Long getId(){return id;} public ProductionLine getLine(){return line;} public String getName(){return name;} public Instant getStartedAt(){return startedAt;} public Instant getEndedAt(){return endedAt;} public BatchStatus getStatus(){return status;}
 public void close(){status=BatchStatus.CLOSED;endedAt=Instant.now();}
}
