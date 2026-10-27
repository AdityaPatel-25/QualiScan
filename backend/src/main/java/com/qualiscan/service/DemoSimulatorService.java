package com.qualiscan.service;

import com.qualiscan.domain.InspectionResult;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.domain.Verdict;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Live simulator running exclusively in the "demo" profile.
 * Periodically generates synthetic inspections through InspectionService, causing alerts
 * and WebSocket events to naturally trigger and auto-resolve based on a 10-minute sine wave defect rate.
 * Also performs scheduled retention cleanup keeping at most 5,000 inspections and 100 snapshots.
 */
@Service
@Profile("demo")
public class DemoSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(DemoSimulatorService.class);

    private final InspectionService inspectionService;
    private final ProductionLineRepository lineRepository;
    private final InspectionRepository inspectionRepository;
    private final boolean simulatorEnabled;
    private final Path uploadDir;

    private final AtomicLong trackSequence = new AtomicLong(1000);
    private final List<Resource> bundledSnapshots = new ArrayList<>();

    public DemoSimulatorService(
            InspectionService inspectionService,
            ProductionLineRepository lineRepository,
            InspectionRepository inspectionRepository,
            @Value("${demo.simulator.enabled:true}") boolean simulatorEnabled,
            @Value("${qualiscan.upload-dir:./data/demo-uploads/snapshots}") String uploadDir) {
        this.inspectionService = inspectionService;
        this.lineRepository = lineRepository;
        this.inspectionRepository = inspectionRepository;
        this.simulatorEnabled = simulatorEnabled;
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        loadBundledSnapshots();
        if (simulatorEnabled) {
            log.info("Demo live simulator started (enabled=true, interval=3-5s jitter)");
        } else {
            log.info("Demo live simulator is disabled by configuration");
        }
    }

    private void loadBundledSnapshots() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] jpgs = resolver.getResources("classpath*:/demo/snapshots/*.jpg");
            Resource[] jpegs = resolver.getResources("classpath*:/demo/snapshots/*.jpeg");
            if (jpgs != null) Collections.addAll(bundledSnapshots, jpgs);
            if (jpegs != null) Collections.addAll(bundledSnapshots, jpegs);
        } catch (IOException e) {
            log.warn("Could not load bundled snapshots for simulator: {}", e.getMessage());
        }
    }

    /**
     * Scheduled simulation tick running every 3 to 5 seconds with random jitter.
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledTick() {
        if (!simulatorEnabled) {
            return;
        }

        try {
            long jitterMs = ThreadLocalRandom.current().nextLong(2000);
            Thread.sleep(jitterMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            generateInspection();
        } catch (Exception e) {
            log.error("Simulator inspection generation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Generates a single synthetic inspection through the normal InspectionService pipeline.
     */
    public InspectionResult generateInspection() {
        ProductionLine line = lineRepository.findByName("Line 1").orElse(null);
        if (line == null) {
            return null;
        }

        String[] products = new String[]{"cap", "card", "biscuit"};
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        String productType = products[rand.nextInt(products.length)];

        // Oscillating defect rate between ~5% and ~30% over 600 seconds (10 minutes)
        long epochSecond = Instant.now().getEpochSecond();
        double angle = 2.0 * Math.PI * (epochSecond % 600) / 600.0;
        double rejectProb = 0.175 + (0.125 * Math.sin(angle));

        boolean isReject = rand.nextDouble() < rejectProb;
        Verdict verdict = isReject ? Verdict.REJECT : Verdict.PASS;

        List<String> failedChecks = isReject ? List.of(pickDefect(productType, rand)) : Collections.emptyList();
        double confidence = 0.94 + (rand.nextDouble() * 0.05);
        double area = getSampleArea(productType, rand);
        double circularity = getSampleCircularity(productType, rand);
        double aspectRatio = getSampleAspectRatio(productType, rand);

        MultipartFile snapshot = null;
        if (isReject && !bundledSnapshots.isEmpty()) {
            Resource sample = bundledSnapshots.get(rand.nextInt(bundledSnapshots.size()));
            snapshot = createMultipartFromResource(sample);
        }

        InspectionService.InspectionData data = new InspectionService.InspectionData(
                "sim-" + trackSequence.incrementAndGet(),
                productType,
                confidence,
                verdict,
                failedChecks,
                area,
                circularity,
                aspectRatio,
                Instant.now()
        );

        return inspectionService.recordInspection(line.getId(), data, snapshot);
    }

    /**
     * Scheduled retention cleanup: maintains max 5,000 inspections and 100 snapshots.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cleanupRetention() {
        try {
            // 1. Maintain at most 5,000 inspections
            long totalCount = inspectionRepository.count();
            if (totalCount > 5000) {
                int excess = (int) (totalCount - 5000);
                Page<InspectionResult> oldestPage = inspectionRepository.findAll(
                        PageRequest.of(0, excess, Sort.by("id").ascending()));
                List<InspectionResult> toDelete = oldestPage.getContent();
                for (InspectionResult item : toDelete) {
                    deleteSnapshotFile(item.getSnapshotPath());
                }
                inspectionRepository.deleteAll(toDelete);
                log.info("Retention cleanup: pruned {} oldest inspections", toDelete.size());
            }

            // 2. Maintain at most 100 snapshots on disk
            List<InspectionResult> withSnapshots = inspectionRepository.findWithSnapshotDesc();
            if (withSnapshots.size() > 100) {
                for (int i = 100; i < withSnapshots.size(); i++) {
                    InspectionResult item = withSnapshots.get(i);
                    deleteSnapshotFile(item.getSnapshotPath());
                }
            }

            pruneUploadDirectory(100);
        } catch (Exception e) {
            log.error("Retention cleanup failed: {}", e.getMessage(), e);
        }
    }

    private void deleteSnapshotFile(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(pathStr));
        } catch (IOException e) {
            log.warn("Could not delete snapshot file {}: {}", pathStr, e.getMessage());
        }
    }

    private void pruneUploadDirectory(int maxFiles) {
        if (!Files.exists(uploadDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(uploadDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();

            if (files.size() > maxFiles) {
                for (int i = maxFiles; i < files.size(); i++) {
                    Files.deleteIfExists(files.get(i));
                }
            }
        } catch (IOException e) {
            log.warn("Could not prune upload directory {}: {}", uploadDir, e.getMessage());
        }
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

    private MultipartFile createMultipartFromResource(Resource resource) {
        return new MultipartFile() {
            @Override public String getName() { return "snapshot"; }
            @Override public String getOriginalFilename() { return resource.getFilename() != null ? resource.getFilename() : "snapshot.jpg"; }
            @Override public String getContentType() { return "image/jpeg"; }
            @Override public boolean isEmpty() {
                try { return resource.contentLength() == 0; } catch (IOException e) { return false; }
            }
            @Override public long getSize() {
                try { return resource.contentLength(); } catch (IOException e) { return 0; }
            }
            @Override public byte[] getBytes() throws IOException {
                return resource.getInputStream().readAllBytes();
            }
            @Override public InputStream getInputStream() throws IOException {
                return resource.getInputStream();
            }
            @Override public void transferTo(File dest) throws IOException, IllegalStateException {
                Files.copy(resource.getInputStream(), dest.toPath());
            }
        };
    }
}
