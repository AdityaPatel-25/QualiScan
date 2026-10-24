package com.qualiscan.service;

import com.qualiscan.domain.ProductionLine;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.security.Crypto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Service managing production lines, settings, and API-key hashing/rotation.
 */
@Service
@Transactional
public class ProductionLineService {

    private final ProductionLineRepository lineRepository;

    public ProductionLineService(ProductionLineRepository lineRepository) {
        this.lineRepository = lineRepository;
    }

    /**
     * Lists all production lines.
     */
    @Transactional(readOnly = true)
    public List<ProductionLine> getAllLines() {
        return lineRepository.findAll();
    }

    /**
     * Finds a production line by ID or throws 404 NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public ProductionLine getLineById(Long id) {
        return lineRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
    }

    /**
     * Updates quality thresholds and alert window for a production line.
     */
    public ProductionLine updateSettings(Long id, double defectRateThreshold, int alertWindowMinutes) {
        ProductionLine line = getLineById(id);
        line.setSettings(defectRateThreshold, alertWindowMinutes);
        return lineRepository.save(line);
    }

    /**
     * Generates a new API key, hashes it with SHA-256, stores the hash on the line,
     * and returns the plain API key (which is shown once).
     */
    public String rotateApiKey(Long id) {
        ProductionLine line = getLineById(id);
        String plainKey = Crypto.newApiKey();
        line.setApiKeyHash(Crypto.sha256(plainKey));
        lineRepository.save(line);
        return plainKey;
    }
}
