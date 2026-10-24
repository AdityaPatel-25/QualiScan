package com.qualiscan.repo;

import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.Verdict;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom implementation for aggregating inspection metrics in the database
 * using the unified Specification filter.
 */
@Repository
public class InspectionRepositoryImpl implements InspectionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    public InspectionRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public InspectionRepositoryImpl() {}

    @Override
    public List<Object[]> aggregateVerdicts(Specification<InspectionResult> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<InspectionResult> root = cq.from(InspectionResult.class);

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, cq, cb);
            if (predicate != null) {
                cq.where(predicate);
            }
        }

        cq.multiselect(root.get("verdict"), cb.count(root));
        cq.groupBy(root.get("verdict"));

        return entityManager.createQuery(cq).getResultList();
    }

    @Override
    public List<Object[]> aggregateTypeVerdicts(Specification<InspectionResult> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<InspectionResult> root = cq.from(InspectionResult.class);

        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, cq, cb);
            if (predicate != null) {
                cq.where(predicate);
            }
        }

        cq.multiselect(root.get("productType"), root.get("verdict"), cb.count(root));
        cq.groupBy(root.get("productType"), root.get("verdict"));

        return entityManager.createQuery(cq).getResultList();
    }

    @Override
    public List<String> findFailedChecks(Specification<InspectionResult> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<InspectionResult> root = cq.from(InspectionResult.class);

        List<Predicate> predicates = new ArrayList<>();
        if (spec != null) {
            Predicate basePredicate = spec.toPredicate(root, cq, cb);
            if (basePredicate != null) {
                predicates.add(basePredicate);
            }
        }

        predicates.add(cb.equal(root.get("verdict"), Verdict.REJECT));
        predicates.add(cb.isNotNull(root.get("failedChecks")));
        predicates.add(cb.notEqual(cb.trim(root.get("failedChecks")), ""));

        cq.where(predicates.toArray(new Predicate[0]));
        cq.select(root.get("failedChecks"));

        return entityManager.createQuery(cq).getResultList();
    }
}
