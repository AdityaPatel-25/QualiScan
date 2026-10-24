package com.qualiscan.repo;
import com.qualiscan.domain.*; import java.time.Instant; import java.util.*; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface InspectionRepository extends JpaRepository<InspectionResult,Long>{
 @Query("select i from InspectionResult i where (:lineId is null or i.line.id=:lineId) and (:type is null or i.productType=:type) and (:verdict is null or i.verdict=:verdict) and (:fromTime is null or i.inspectedAt>=:fromTime) and (:toTime is null or i.inspectedAt<=:toTime)") Page<InspectionResult> search(@Param("lineId") Long lineId,@Param("type") String type,@Param("verdict") Verdict verdict,@Param("fromTime") Instant from,@Param("toTime") Instant to,Pageable pageable);
 @Query("select count(i) from InspectionResult i where (:lineId is null or i.line.id=:lineId) and i.inspectedAt>=:start") long countSince(@Param("lineId") Long lineId,@Param("start") Instant start);
 @Query("select i.verdict,count(i) from InspectionResult i where (:lineId is null or i.line.id=:lineId) and i.inspectedAt>=:start group by i.verdict") List<Object[]> verdictSince(@Param("lineId") Long lineId,@Param("start") Instant start);
 @Query("select i.productType,i.verdict,count(i) from InspectionResult i where (:lineId is null or i.line.id=:lineId) and i.inspectedAt>=:start group by i.productType,i.verdict") List<Object[]> typeVerdictSince(@Param("lineId") Long lineId,@Param("start") Instant start);
 @Query("select count(i) from InspectionResult i where i.batch.id=:batchId") long countBatch(@Param("batchId") Long id);
 @Query("select i.verdict,count(i) from InspectionResult i where i.batch.id=:batchId group by i.verdict") List<Object[]> verdictBatch(@Param("batchId") Long id);
 @Query("select i.productType,count(i) from InspectionResult i where i.batch.id=:batchId group by i.productType") List<Object[]> typeBatch(@Param("batchId") Long id);
 @Query("select i.failedChecks from InspectionResult i where i.batch.id=:batchId and i.verdict='REJECT' and i.failedChecks is not null") List<String> failedBatch(@Param("batchId") Long id);
 @Query("select hour(i.inspectedAt),i.verdict,count(i) from InspectionResult i where i.line.id=:lineId and i.inspectedAt>=:start and i.inspectedAt<:end and i.verdict in ('PASS','REJECT') group by hour(i.inspectedAt),i.verdict") List<Object[]> hourly(@Param("lineId") Long id,@Param("start") Instant start,@Param("end") Instant end);
}
