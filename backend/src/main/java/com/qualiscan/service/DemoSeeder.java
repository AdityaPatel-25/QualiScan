package com.qualiscan.service;

import com.qualiscan.domain.*;
import com.qualiscan.repo.*;
import com.qualiscan.security.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup seeder active exclusively under the "demo" profile.
 * Creates a single read-only operator account, an active line, three product profiles,
 * one open batch, and backfills ~600 realistic inspection results over the previous 24 hours.
 */
@Component
@Profile("demo")
public class DemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final Environment env;
    private final AppUserRepository userRepository;
    private final ProductionLineRepository lineRepository;
    private final ProductProfileRepository profileRepository;
    private final BatchRepository batchRepository;
    private final InspectionRepository inspectionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Path uploadDir;

    public DemoSeeder(
            Environment env,
            AppUserRepository userRepository,
            ProductionLineRepository lineRepository,
            ProductProfileRepository profileRepository,
            BatchRepository batchRepository,
            InspectionRepository inspectionRepository,
            PasswordEncoder passwordEncoder,
            @Value("${qualiscan.upload-dir:./data/demo-uploads/snapshots}") String uploadDir) {
        this.env = env;
        this.userRepository = userRepository;
        this.lineRepository = lineRepository;
        this.profileRepository = profileRepository;
        this.batchRepository = batchRepository;
        this.inspectionRepository = inspectionRepository;
        this.passwordEncoder = passwordEncoder;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // 1. Validate DEMO_OPERATOR_PASSWORD
        String password = env.getProperty("DEMO_OPERATOR_PASSWORD");
        if (password == null || password.isBlank()) {
            password = env.getProperty("demo.operator.password");
        }
        if (password == null || password.trim().length() < 12) {
            throw new IllegalStateException("DEMO_OPERATOR_PASSWORD environment variable is missing or shorter than 12 characters");
        }

        // 2. Create single OPERATOR user if not already present; never create admin/supervisor in demo profile
        if (userRepository.findByUsername("operator").isEmpty()) {
            userRepository.save(new AppUser("operator", passwordEncoder.encode(password.trim()), Role.OPERATOR));
        }

        // 3. Ensure Line 1 exists with a random device key that is never logged or printed
        ProductionLine line = lineRepository.findByName("Line 1").orElseGet(() -> {
            String secretApiKey = Crypto.newApiKey();
            String keyHash = Crypto.sha256(secretApiKey);
            return lineRepository.save(new ProductionLine("Line 1", keyHash));
        });

        // 4. Seed standard product profiles
        if (profileRepository.findByProductType("cap").isEmpty()) {
            profileRepository.save(new ProductProfile("cap",
                    "{\"product\":\"cap\",\"_note\":\"Demo profile thresholds.\",\"min_area\":800,\"max_area\":35000,\"circularity_range\":[0.65,1.05],\"aspect_ratio_range\":[0.8,1.3],\"solidity_range\":[0.85,1.0],\"min_solidity\":0.85,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"dent\"]}"));
        }
        if (profileRepository.findByProductType("card").isEmpty()) {
            profileRepository.save(new ProductProfile("card",
                    "{\"product\":\"card\",\"_note\":\"Demo profile thresholds.\",\"min_area\":800,\"max_area\":35000,\"circularity_range\":[0.1,0.95],\"aspect_ratio_range\":[1.2,2.2],\"solidity_range\":[0.85,1.0],\"max_skew_deg\":8,\"max_diff_ratio\":0.12,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"orientation\",\"template_match\"]}"));
        }
        if (profileRepository.findByProductType("biscuit").isEmpty()) {
            profileRepository.save(new ProductProfile("biscuit",
                    "{\"product\":\"biscuit\",\"_note\":\"Demo profile thresholds.\",\"min_area\":800,\"max_area\":35000,\"circularity_range\":[0.3,1.05],\"aspect_ratio_range\":[0.8,2.2],\"solidity_range\":[0.75,1.0],\"min_solidity\":0.75,\"expected_area_range\":[800,35000],\"max_dark_fraction\":0.15,\"dark_value_threshold\":60,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"broken\",\"burnt\"]}"));
        }

        // 5. Ensure an active open batch exists on Line 1
        Batch activeBatch = null;
        if (line.getActiveBatchId() != null) {
            activeBatch = batchRepository.findById(line.getActiveBatchId()).orElse(null);
        }
        if (activeBatch == null) {
            activeBatch = batchRepository.save(new Batch(line, "BATCH-DEMO-001"));
            line.setActiveBatchId(activeBatch.getId());
            lineRepository.save(line);
        }

        // 6. Backfill ~24 hours of realistic inspections (~600 rows) if table is empty
        long existingCount = inspectionRepository.count();
        if (existingCount == 0) {
            List<Resource> bundledSnapshots = loadBundledSnapshots();
            List<InspectionResult> seeded = seedHistoricalInspections(line, activeBatch, bundledSnapshots, 600);
            log.info("Demo profile initialized: seeded operator user, Line 1, active batch {}, and {} historical inspections",
                    activeBatch.getName(), seeded.size());
        } else {
            log.info("Demo profile database already populated with {} inspections", existingCount);
        }
    }

    /**
     * Finds bundled snapshot JPEG crops from classpath, skipping gracefully if empty.
     */
    private List<Resource> loadBundledSnapshots() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] jpgs = resolver.getResources("classpath*:/demo/snapshots/*.jpg");
            Resource[] jpegs = resolver.getResources("classpath*:/demo/snapshots/*.jpeg");
            List<Resource> list = new ArrayList<>();
            if (jpgs != null) Collections.addAll(list, jpgs);
            if (jpegs != null) Collections.addAll(list, jpegs);
            return list;
        } catch (IOException e) {
            log.warn("Could not scan for demo snapshots: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Seeds backfilled inspection records over the last 24 hours with realistic defect rates and checks.
     */
    private List<InspectionResult> seedHistoricalInspections(
            ProductionLine line,
            Batch batch,
            List<Resource> snapshotResources,
            int targetCount) {

        List<InspectionResult> results = new ArrayList<>(targetCount);
        Instant now = Instant.now();
        Instant startTime = now.minus(Duration.ofHours(24));
        long totalSeconds = 24 * 3600;
        long stepSeconds = Math.max(1, totalSeconds / targetCount);

        String[] products = new String[]{"cap", "card", "biscuit"};
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.warn("Could not create snapshot upload directory {}: {}", uploadDir, e.getMessage());
        }

        int snapshotIndex = 0;

        for (int i = 0; i < targetCount; i++) {
            Instant inspectedAt = startTime.plusSeconds((i * stepSeconds) + rand.nextLong(-15, 16));
            if (inspectedAt.isAfter(now)) {
                inspectedAt = now.minusSeconds(rand.nextLong(1, 30));
            }

            String productType = products[i % products.length];
            String trackId = String.format("track-seed-%04d", i + 1);

            // Realistic defect rate roughly 6% to 12%
            boolean isReject = rand.nextDouble() < 0.085;
            Verdict verdict = isReject ? Verdict.REJECT : Verdict.PASS;

            String failedChecks = null;
            if (isReject) {
                failedChecks = pickDefect(productType, rand);
            }

            double confidence = 0.92 + (rand.nextDouble() * 0.07);
            double area = getSampleArea(productType, rand);
            double circularity = getSampleCircularity(productType, rand);
            double aspectRatio = getSampleAspectRatio(productType, rand);

            String snapshotPath = null;
            if (isReject && !snapshotResources.isEmpty()) {
                Resource resource = snapshotResources.get(snapshotIndex % snapshotResources.size());
                snapshotIndex++;
                snapshotPath = copySnapshotFile(resource);
            }

            InspectionResult result = new InspectionResult(
                    line,
                    batch,
                    trackId,
                    productType,
                    confidence,
                    verdict,
                    failedChecks,
                    area,
                    circularity,
                    aspectRatio,
                    snapshotPath,
                    inspectedAt
            );
            results.add(result);
        }

        return inspectionRepository.saveAll(results);
    }

    private String pickDefect(String productType, ThreadLocalRandom rand) {
        return switch (productType) {
            case "cap" -> rand.nextBoolean() ? "dent" : "size";
            case "card" -> rand.nextBoolean() ? "template" : "orientation";
            case "biscuit" -> rand.nextBoolean() ? "broken" : "burnt";
            default -> "defect";
        };
    }

    private double getSampleArea(String productType, ThreadLocalRandom rand) {
        return switch (productType) {
            case "cap" -> 12000 + rand.nextDouble(-1000, 1000);
            case "card" -> 18000 + rand.nextDouble(-1500, 1500);
            case "biscuit" -> 14000 + rand.nextDouble(-1200, 1200);
            default -> 10000;
        };
    }

    private double getSampleCircularity(String productType, ThreadLocalRandom rand) {
        return switch (productType) {
            case "cap" -> 0.88 + rand.nextDouble(-0.04, 0.04);
            case "card" -> 0.65 + rand.nextDouble(-0.05, 0.05);
            case "biscuit" -> 0.91 + rand.nextDouble(-0.03, 0.03);
            default -> 0.8;
        };
    }

    private double getSampleAspectRatio(String productType, ThreadLocalRandom rand) {
        return switch (productType) {
            case "cap" -> 1.0 + rand.nextDouble(-0.04, 0.04);
            case "card" -> 1.6 + rand.nextDouble(-0.08, 0.08);
            case "biscuit" -> 1.02 + rand.nextDouble(-0.05, 0.05);
            default -> 1.0;
        };
    }

    private String copySnapshotFile(Resource resource) {
        try {
            Path target = uploadDir.resolve(UUID.randomUUID() + ".jpg").normalize();
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (IOException e) {
            log.warn("Could not copy bundled snapshot: {}", e.getMessage());
            return null;
        }
    }
}
