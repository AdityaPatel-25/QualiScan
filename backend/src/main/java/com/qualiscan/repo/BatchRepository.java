package com.qualiscan.repo;
import com.qualiscan.domain.*; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface BatchRepository extends JpaRepository<Batch,Long>{List<Batch> findByLineIdOrderByStartedAtDesc(Long lineId);Optional<Batch> findFirstByLineIdAndStatus(Long lineId,BatchStatus status);}
