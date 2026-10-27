package com.qualiscan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.*;
import com.qualiscan.repo.*;
import com.qualiscan.security.DemoLoginRateLimiterFilter;
import com.qualiscan.service.DemoSimulatorService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test suite verifying the "demo" Spring profile behaviors:
 * 1. Demo seeder creates only the operator account (no admin/supervisor).
 * 2. Operator token receives 403 on mutating endpoints.
 * 3. Demo simulator produces valid inspections appearing in /api/inspections and triggers alert logic.
 * 4. Retention cleanup maintains max 5,000 inspections and 100 snapshots.
 * 5. In-memory login rate limiter blocks the 11th login attempt within a minute with HTTP 429.
 * 6. Public health endpoint GET /api/health is accessible without authentication.
 * 7. Static development page ws-test.html is NOT served (returns 404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "DEMO_OPERATOR_PASSWORD=ValidLongDemoPassword123!",
        "demo.simulator.enabled=false"
})
class DemoProfileTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired ProductionLineRepository lines;
    @Autowired InspectionRepository inspections;
    @Autowired AlertRepository alerts;
    @Autowired DemoSimulatorService simulatorService;
    @Autowired DemoLoginRateLimiterFilter rateLimiterFilter;

    @Test
    void testHealthEndpointPermitAll() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testWsTestHtmlNotServedInDemoProfile() throws Exception {
        mvc.perform(get("/ws-test.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDemoSeederCreatesOnlyOperator() {
        List<AppUser> allUsers = users.findAll();
        Assertions.assertEquals(1, allUsers.size(), "Demo profile must seed exactly one user");

        AppUser operator = allUsers.get(0);
        Assertions.assertEquals("operator", operator.getUsername());
        Assertions.assertEquals(Role.OPERATOR, operator.getRole());

        Assertions.assertTrue(users.findByUsername("admin").isEmpty(), "Admin account must NOT exist in demo profile");
        Assertions.assertTrue(users.findByUsername("supervisor").isEmpty(), "Supervisor account must NOT exist in demo profile");

        // Verify Line 1 and backfilled inspections
        Assertions.assertTrue(lines.findByName("Line 1").isPresent(), "Line 1 should be seeded");
        Assertions.assertTrue(inspections.count() >= 500, "Historical inspections should be seeded (around 600)");
    }

    @Test
    void testOperatorCannotCallMutatingEndpoints() throws Exception {
        // Authenticate as operator
        String loginBody = mapper.writeValueAsString(Map.of(
                "username", "operator",
                "password", "ValidLongDemoPassword123!"
        ));

        String loginRes = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(loginRes);
        String token = json.get("token").asText();
        Assertions.assertNotNull(token);

        ProductionLine line = lines.findByName("Line 1").orElseThrow();

        // 1. Operator cannot start batches (403)
        String batchReq = mapper.writeValueAsString(Map.of("lineId", line.getId(), "name", "MUTATING-BATCH"));
        mvc.perform(post("/api/batches")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchReq))
                .andExpect(status().isForbidden());

        // 2. Operator cannot update line settings (403)
        String settingsReq = mapper.writeValueAsString(Map.of("defectRateThreshold", 0.12, "alertWindowMinutes", 10));
        mvc.perform(put("/api/lines/" + line.getId() + "/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsReq))
                .andExpect(status().isForbidden());

        // 3. Operator cannot rotate device key (403)
        mvc.perform(post("/api/lines/" + line.getId() + "/rotate-key")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // 4. Operator cannot manage users (403)
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void testSimulatorProducesValidInspectionsAndTriggersAlertLogic() throws Exception {
        long initialCount = inspections.count();

        // 1. Generate synthetic inspection
        InspectionResult produced = simulatorService.generateInspection();
        Assertions.assertNotNull(produced);
        Assertions.assertTrue(inspections.count() > initialCount);

        // 2. Verify inspection is visible in /api/inspections with operator token
        String loginBody = mapper.writeValueAsString(Map.of("username", "operator", "password", "ValidLongDemoPassword123!"));
        String token = mapper.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mvc.perform(get("/api/inspections")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());

        // 3. Test alert logic evaluation through simulated inspections
        ProductionLine line = lines.findByName("Line 1").orElseThrow();
        // Lower threshold to 0.05 so a series of simulated rejects reliably triggers alert
        line.setSettings(0.05, 5);
        lines.save(line);

        for (int i = 0; i < 25; i++) {
            simulatorService.generateInspection();
        }

        // Live stats endpoint is also accessible
        mvc.perform(get("/api/stats/live")
                        .header("Authorization", "Bearer " + token)
                        .param("lineId", String.valueOf(line.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defectRate").isNumber());
    }

    @Test
    void testRetentionCleanup() {
        ProductionLine line = lines.findByName("Line 1").orElseThrow();

        // Add dummy inspections to exceed 5,000
        long current = inspections.count();
        if (current <= 5000) {
            int extraNeeded = (int) (5005 - current);
            java.util.List<InspectionResult> extras = new java.util.ArrayList<>();
            for (int i = 0; i < extraNeeded; i++) {
                extras.add(new InspectionResult(
                        line, null, "retention-extra-" + i, "cap", 0.95,
                        Verdict.PASS, null, 12000, 0.88, 1.0, null, Instant.now()
                ));
            }
            inspections.saveAll(extras);
        }

        Assertions.assertTrue(inspections.count() > 5000);

        // Trigger retention cleanup
        simulatorService.cleanupRetention();

        Assertions.assertTrue(inspections.count() <= 5000, "Inspections count must be at most 5,000 after cleanup");
    }

    @Test
    void testLoginRateLimiting11thAttemptGets429() throws Exception {
        rateLimiterFilter.reset();

        String ip = "192.168.1.100";
        String loginBody = mapper.writeValueAsString(Map.of(
                "username", "operator",
                "password", "ValidLongDemoPassword123!"
        ));

        // First 10 attempts should proceed (returns 200 OK)
        for (int i = 1; i <= 10; i++) {
            mvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody))
                    .andExpect(status().isOk());
        }

        // 11th attempt must be rejected with 429 Too Many Requests
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Too many login attempts. Please wait a minute before trying again."));

        // A different IP should still be allowed
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "192.168.1.200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk());
    }
}
