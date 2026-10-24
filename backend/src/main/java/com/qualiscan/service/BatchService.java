package com.qualiscan.service;

import com.qualiscan.domain.Batch;
import com.qualiscan.domain.BatchStatus;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.repo.BatchRepository;
import com.qualiscan.repo.ProductionLineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Service managing manufacturing batches and batch open/close lifecycle logic.
 */
@Service
@Transactional
public class BatchService {

    private final BatchRepository batchRepository;
    private final ProductionLineRepository lineRepository;

    public BatchService(BatchRepository batchRepository, ProductionLineRepository lineRepository) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
    }

    /**
     * Starts a new batch on the specified line.
     * Automatically closes any currently open batch on that line, and updates the line's activeBatchId.
     */
    public Batch startBatch(Long lineId, String name) {
        ProductionLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        // Close any currently open batch on this line
        batchRepository.findFirstByLineIdAndStatus(line.getId(), BatchStatus.OPEN)
                .ifPresent(Batch::close);

        Batch newBatch = batchRepository.save(new Batch(line, name));
        line.setActiveBatchId(newBatch.getId());
        lineRepository.save(line);

        return newBatch;
    }

    /**
     * Closes an existing batch.
     * Clears the active batch ID on the line if this was the active batch.
     */
    public Batch closeBatch(Long id) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));

        batch.close();
        Batch savedBatch = batchRepository.save(batch);

        ProductionLine line = batch.getLine();
        if (Objects.equals(line.getActiveBatchId(), batch.getId())) {
            line.setActiveBatchId(null);
            lineRepository.save(line);
        }

        return savedBatch;
    }

    /**
     * Lists batches, optionally filtered by line ID, ordered by startedAt descending.
     */
    @Transactional(readOnly = true)
    public List<Batch> getBatches(Long lineId) {
        if (lineId == null) {
            return batchRepository.findAll().stream()
                    .sorted(Comparator.comparing(Batch::getStartedAt).reversed())
                    .toList();
        }

        if (!lineRepository.existsById(lineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found");
        }

        return batchRepository.findByLineIdOrderByStartedAtDesc(lineId);
    }

    /**
     * Gets a batch by ID or throws 404 NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public Batch getBatchById(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
    }

    /**
     * Checks if a batch exists.
     */
    @Transactional(readOnly = true)
    public boolean existsById(Long id) {
        return batchRepository.existsById(id);
    }
}
