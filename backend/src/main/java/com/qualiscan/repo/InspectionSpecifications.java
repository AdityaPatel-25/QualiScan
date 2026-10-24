package com.qualiscan.repo;

import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.Verdict;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic specifications for filtering inspection results consistently across search and reports.
 */
public final class InspectionSpecifications {

    private InspectionSpecifications() {}

    /**
     * Builds a JPA specification appending predicates only when parameters are present.
     * Date filtering is strictly [from inclusive, to exclusive).
     */
    public static Specification<InspectionResult> filter(
            Long lineId,
            String productType,
            Verdict verdict,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (lineId != null) {
                predicates.add(cb.equal(root.get("line").get("id"), lineId));
            }
            if (productType != null && !productType.isBlank()) {
                predicates.add(cb.equal(root.get("productType"), productType));
            }
            if (verdict != null) {
                predicates.add(cb.equal(root.get("verdict"), verdict));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("inspectedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("inspectedAt"), to));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
