package com.qualiscan.repo;
import com.qualiscan.domain.ProductProfile; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface ProductProfileRepository extends JpaRepository<ProductProfile,Long>{Optional<ProductProfile> findByProductType(String type);}
