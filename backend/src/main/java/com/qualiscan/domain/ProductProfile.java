package com.qualiscan.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="product_profiles",indexes=@Index(columnList="productType",unique=true))
public class ProductProfile {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true) private String productType;
 @Lob @Column(nullable=false) private String rulesJson;
 @Column(nullable=false) private Instant updatedAt=Instant.now();
 protected ProductProfile(){} public ProductProfile(String type,String json){productType=type;rulesJson=json;}
 public Long getId(){return id;} public String getProductType(){return productType;} public String getRulesJson(){return rulesJson;} public Instant getUpdatedAt(){return updatedAt;}
 public void update(String json){rulesJson=json;updatedAt=Instant.now();}
}
