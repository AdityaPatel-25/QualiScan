package com.qualiscan.repo;

import com.qualiscan.domain.ShiftSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShiftSummaryRepository extends JpaRepository<ShiftSummary, Long> {

    Optional<ShiftSummary> findByLineIdAndPeriodStartAndPeriodEnd(
            Long lineId,
            Instant periodStart,
            Instant periodEnd
    );

    @Query("select s from ShiftSummary s where (:lineId is null or s.line.id = :lineId)")
    Page<ShiftSummary> search(
            @Param("lineId") Long lineId,
            Pageable pageable
    );
}
