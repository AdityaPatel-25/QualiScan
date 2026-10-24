package com.qualiscan.repo;

import com.qualiscan.domain.Alert;
import com.qualiscan.domain.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    @Query("select a from Alert a where (:lineId is null or a.line.id = :lineId) and (:status is null or a.status = :status)")
    Page<Alert> search(
            @Param("lineId") Long lineId,
            @Param("status") AlertStatus status,
            Pageable pageable
    );

    @Query("select a from Alert a where a.line.id = :lineId and " +
            "((:productType is null and a.productType is null) or a.productType = :productType) and " +
            "a.status in :statuses")
    List<Alert> findActiveAlerts(
            @Param("lineId") Long lineId,
            @Param("productType") String productType,
            @Param("statuses") Collection<AlertStatus> statuses
    );
}
