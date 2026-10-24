package com.qualiscan.domain;
import jakarta.persistence.*;
@Entity @Table(name="production_lines",indexes=@Index(columnList="name",unique=true))
public class ProductionLine {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true) private String name;
 @Column(nullable=false,length=64) private String apiKeyHash;
 private Long activeBatchId;
 @Column(nullable=false) private double defectRateThreshold=0.08;
 @Column(nullable=false) private int alertWindowMinutes=5;
 protected ProductionLine() {}
 public ProductionLine(String name,String apiKeyHash){this.name=name;this.apiKeyHash=apiKeyHash;}
 public Long getId(){return id;} public String getName(){return name;} public String getApiKeyHash(){return apiKeyHash;} public Long getActiveBatchId(){return activeBatchId;} public double getDefectRateThreshold(){return defectRateThreshold;} public int getAlertWindowMinutes(){return alertWindowMinutes;}
 public void setApiKeyHash(String v){apiKeyHash=v;} public void setSettings(double t,int m){defectRateThreshold=t;alertWindowMinutes=m;} public void setActiveBatchId(Long v){activeBatchId=v;}
}
