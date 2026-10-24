package com.qualiscan.repo;

import com.qualiscan.domain.InspectionResult;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * Custom aggregation methods executing dynamic queries directly in the database.
 */
public interface InspectionRepositoryCustom {

    List<Object[]> aggregateVerdicts(Specification<InspectionResult> spec);

    List<Object[]> aggregateTypeVerdicts(Specification<InspectionResult> spec);

    List<String> findFailedChecks(Specification<InspectionResult> spec);
}
