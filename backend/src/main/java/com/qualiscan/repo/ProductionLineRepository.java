package com.qualiscan.repo;
import com.qualiscan.domain.ProductionLine; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface ProductionLineRepository extends JpaRepository<ProductionLine,Long>{Optional<ProductionLine> findByApiKeyHash(String hash);}
