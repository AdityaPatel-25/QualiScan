package com.qualiscan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.ProductProfile;
import com.qualiscan.repo.ProductProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Service managing product inspection profiles and rules.
 */
@Service
@Transactional
public class ProfileService {

    private final ProductProfileRepository profileRepository;
    private final ObjectMapper objectMapper;

    public ProfileService(ProductProfileRepository profileRepository, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists all product profiles.
     */
    @Transactional(readOnly = true)
    public List<ProductProfile> getAllProfiles() {
        return profileRepository.findAll();
    }

    /**
     * Retrieves a profile by product type or throws 404 NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public ProductProfile getProfileByType(String productType) {
        return profileRepository.findByProductType(productType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    /**
     * Updates defect detection rules JSON for a given product type.
     */
    public ProductProfile updateProfile(String productType, String rulesJson) {
        try {
            objectMapper.readTree(rulesJson);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rulesJson must be valid JSON");
        }

        ProductProfile profile = getProfileByType(productType);
        profile.update(rulesJson);
        return profileRepository.save(profile);
    }
}
