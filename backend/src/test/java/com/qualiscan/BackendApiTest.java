package com.qualiscan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.*;
import com.qualiscan.repo.*;
import com.qualiscan.security.Crypto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full API integration test suite covering all QualiScan endpoints, role-based access control,
 * batch management, key rotation, snapshot file retrieval, filtering/paging, and quality statistics.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BackendApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired ProductProfileRepository profiles;
    @Autowired ProductionLineRepository lines;
    @Autowired BatchRepository batches;
    @Autowired InspectionRepository inspections;
    @Autowired PasswordEncoder encoder;

    private String deviceKey;
    private ProductionLine testLine;

    @BeforeEach
    void prepare() {
        inspections.deleteAll();
        batches.deleteAll();

        users.findByUsername("admin").orElseGet(() ->
                users.save(new AppUser("admin", encoder.encode("Admin@123"), Role.ADMIN)));
        users.findByUsername("operator").orElseGet(() ->
                users.save(new AppUser("operator", encoder.encode("Oper@123"), Role.OPERATOR)));
        users.findByUsername("supervisor").orElseGet(() ->
                users.save(new AppUser("supervisor", encoder.encode("Super@123"), Role.SUPERVISOR)));

        if (profiles.findByProductType("cap").isEmpty()) {
            profiles.save(new ProductProfile("cap", "{\"min_area\":800,\"max_area\":35000}"));
        }
        if (profiles.findByProductType("card").isEmpty()) {
            profiles.save(new ProductProfile("card", "{\"min_area\":800,\"max_area\":35000}"));
        }

        deviceKey = "test-device-key-0123456789-abcdefghijklmnopqrstuvwxyz";
        String hash = Crypto.sha256(deviceKey);
        testLine = lines.findAll().stream()
                .filter(l -> "Test line".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lines.save(new ProductionLine("Test line", hash)));
        testLine.setApiKeyHash(hash);
        testLine.setActiveBatchId(null);
        testLine = lines.save(testLine);
    }

    private String token(String username, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new Login(username, password))))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("token").asText();
    }

    record Login(String username, String password) {}

    private MockMultipartFile result(String verdict, String track) throws Exception {
        return result(verdict, track, "cap", verdict.equals("REJECT") ? new String[]{"scratch"} : new String[]{});
    }

    private MockMultipartFile result(String verdict, String track, String productType, String[] failedChecks) throws Exception {
        return new MockMultipartFile(
                "result",
                "result.json",
                "application/json",
                mapper.writeValueAsBytes(new Payload(
                        track,
                        productType,
                        0.99,
                        verdict,
                        failedChecks,
                        100,
                        0.8,
                        1.1,
                        Instant.now().toString()
                ))
        );
    }

    record Payload(
            String trackId,
            String productType,
            double confidence,
            String verdict,
            String[] failedChecks,
            double area,
            double circularity,
            double aspectRatio,
            String inspectedAt
    ) {}

    // 1. Authentication tests
    @Test
    void loginWorksAndRejectsBadPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized());
    }

    // 2. Profiles RBAC & retrieval
    @Test
    void profileWritesRespectRolesAndReadsWork() throws Exception {
        String operator = token("operator", "Oper@123");
        String supervisor = token("supervisor", "Super@123");

        // Operator cannot update profiles
        mvc.perform(put("/api/profiles/cap")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesJson\":\"{}\"}"))
                .andExpect(status().isForbidden());

        // Supervisor can update profiles
        mvc.perform(put("/api/profiles/cap")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rulesJson\":\"{\\\"min_area\\\":900}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productType").value("cap"))
                .andExpect(jsonPath("$.rulesJson").value("{\"min_area\":900}"));

        // Authenticated user can read profiles
        mvc.perform(get("/api/profiles").header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/profiles/cap").header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productType").value("cap"));

        // Device key can also read profiles
        mvc.perform(get("/api/profiles").header("X-API-Key", deviceKey))
                .andExpect(status().isOk());

        mvc.perform(get("/api/profiles/cap").header("X-API-Key", deviceKey))
                .andExpect(status().isOk());
    }

    // 3. Line settings update (SUPERVISOR ok, OPERATOR gets 403)
    @Test
    void lineSettingsUpdateRoleCheck() throws Exception {
        String supervisor = token("supervisor", "Super@123");
        String operator = token("operator", "Oper@123");
        Long lineId = testLine.getId();

        // Reading line details is allowed for authenticated users
        mvc.perform(get("/api/lines").header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + lineId + ")]").exists());

        mvc.perform(get("/api/lines/" + lineId).header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(testLine.getName()));

        // Operator gets 403 Forbidden when trying to update settings
        mvc.perform(put("/api/lines/" + lineId + "/settings")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defectRateThreshold\":0.15,\"alertWindowMinutes\":10}"))
                .andExpect(status().isForbidden());

        // Supervisor is authorized (200 OK)
        mvc.perform(put("/api/lines/" + lineId + "/settings")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defectRateThreshold\":0.15,\"alertWindowMinutes\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defectRateThreshold").value(0.15))
                .andExpect(jsonPath("$.alertWindowMinutes").value(10));
    }

    // 4. Key rotation (ADMIN only; the old key stops working afterwards)
    @Test
    void keyRotationAdminOnlyAndOldKeyStopsWorking() throws Exception {
        String operator = token("operator", "Oper@123");
        String supervisor = token("supervisor", "Super@123");
        String admin = token("admin", "Admin@123");
        Long lineId = testLine.getId();

        // Operator and Supervisor get 403
        mvc.perform(post("/api/lines/" + lineId + "/rotate-key")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/lines/" + lineId + "/rotate-key")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isForbidden());

        // Device key works prior to rotation
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "pre-rotate"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        // Admin rotates key
        String rotateResponse = mvc.perform(post("/api/lines/" + lineId + "/rotate-key")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String newKey = mapper.readTree(rotateResponse).get("apiKey").asText();
        Assertions.assertNotEquals(deviceKey, newKey);

        // Old key no longer works (401 Unauthorized)
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "post-rotate-old-key"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isUnauthorized());

        // New key works (201 Created)
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "post-rotate-new-key"))
                        .header("X-API-Key", newKey))
                .andExpect(status().isCreated());
    }

    // 5. Batch start/close (starting a new batch closes the open one)
    @Test
    void batchStartAndCloseLifecycle() throws Exception {
        String supervisor = token("supervisor", "Super@123");
        Long lineId = testLine.getId();

        // 1. Start Batch A
        String batchAResponse = mvc.perform(post("/api/batches")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":" + lineId + ",\"name\":\"Batch-A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Batch-A"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();

        long batchAId = mapper.readTree(batchAResponse).get("id").asLong();

        // Verify line activeBatchId is batchA
        mvc.perform(get("/api/lines/" + lineId).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBatchId").value(batchAId));

        // 2. Start Batch B (must close Batch A)
        String batchBResponse = mvc.perform(post("/api/batches")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":" + lineId + ",\"name\":\"Batch-B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Batch-B"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();

        long batchBId = mapper.readTree(batchBResponse).get("id").asLong();

        // Verify in batch list that Batch A is now CLOSED
        String listResponse = mvc.perform(get("/api/batches")
                        .param("lineId", String.valueOf(lineId))
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode batchList = mapper.readTree(listResponse);
        boolean batchAWasClosed = false;
        for (JsonNode b : batchList) {
            if (b.get("id").asLong() == batchAId && "CLOSED".equals(b.get("status").asText())) {
                batchAWasClosed = true;
            }
        }
        Assertions.assertTrue(batchAWasClosed, "Starting a new batch must close the open one");

        // Verify line activeBatchId is now batchB
        mvc.perform(get("/api/lines/" + lineId).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBatchId").value(batchBId));

        // 3. Close Batch B
        mvc.perform(post("/api/batches/" + batchBId + "/close")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());

        // Verify line activeBatchId is now cleared
        mvc.perform(get("/api/lines/" + lineId).header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBatchId").value(""));
    }

    // 6. Snapshot retrieval (authenticated only; 404 for a missing id)
    @Test
    void snapshotRetrievalAuthenticatedOnlyAndNotFound() throws Exception {
        byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9};
        MockMultipartFile snap = new MockMultipartFile("snapshot", "sample.jpg", "image/jpeg", jpegBytes);

        String postRes = mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "track-with-snap"))
                        .file(snap)
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long inspectionId = mapper.readTree(postRes).get("id").asLong();

        // Unauthenticated access gets 401 Unauthorized
        mvc.perform(get("/api/snapshots/" + inspectionId))
                .andExpect(status().isUnauthorized());

        // Authenticated access returns JPEG snapshot
        String operator = token("operator", "Oper@123");
        byte[] returnedBytes = mvc.perform(get("/api/snapshots/" + inspectionId)
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        Assertions.assertArrayEquals(jpegBytes, returnedBytes);

        // Missing ID gets 404 Not Found
        mvc.perform(get("/api/snapshots/999999")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isNotFound());

        // Inspection without snapshot gets 404 Not Found
        String postNoSnap = mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "track-no-snap"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long noSnapId = mapper.readTree(postNoSnap).get("id").asLong();

        mvc.perform(get("/api/snapshots/" + noSnapId)
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isNotFound());
    }

    // 7. GET /api/stats/batch/{id} and GET /api/stats/hourly with known data (assert the exact numbers)
    @Test
    void statsBatchAndHourlyWithKnownData() throws Exception {
        String supervisor = token("supervisor", "Super@123");
        String admin = token("admin", "Admin@123");
        Long lineId = testLine.getId();

        // Start a batch so inspections associate with it
        String batchRes = mvc.perform(post("/api/batches")
                        .header("Authorization", "Bearer " + supervisor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lineId\":" + lineId + ",\"name\":\"Stats-Batch\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long batchId = mapper.readTree(batchRes).get("id").asLong();

        // Post 4 known inspections: 2 PASS (cap), 1 REJECT (cap: scratch, dent), 1 REJECT (card: dent)
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "track-p1", "cap", new String[]{}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "track-p2", "cap", new String[]{}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("REJECT", "track-r1", "cap", new String[]{"scratch", "dent"}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("REJECT", "track-r2", "card", new String[]{"dent"}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        // Assert exact batch stats numbers:
        // total: 4, pass: 2, reject: 2, unknown: 0, defectRate: 2 / (2 + 2) = 0.5
        // defectsByType: scratch: 1, dent: 2
        // countsPerProductType: cap: 3, card: 1
        mvc.perform(get("/api/stats/batch/" + batchId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.pass").value(2))
                .andExpect(jsonPath("$.reject").value(2))
                .andExpect(jsonPath("$.unknown").value(0))
                .andExpect(jsonPath("$.defectRate").value(0.5))
                .andExpect(jsonPath("$.defectsByType.scratch").value(1))
                .andExpect(jsonPath("$.defectsByType.dent").value(2))
                .andExpect(jsonPath("$.countsPerProductType.cap").value(3))
                .andExpect(jsonPath("$.countsPerProductType.card").value(1));

        // Assert hourly stats with exact known numbers for today
        LocalDate today = LocalDate.now();
        String hourlyRes = mvc.perform(get("/api/stats/hourly")
                        .param("lineId", String.valueOf(lineId))
                        .param("date", today.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24))
                .andReturn().getResponse().getContentAsString();

        JsonNode hourlyArray = mapper.readTree(hourlyRes);
        long totalHourlyPass = 0;
        long totalHourlyReject = 0;
        for (JsonNode h : hourlyArray) {
            totalHourlyPass += h.get("pass").asLong();
            totalHourlyReject += h.get("reject").asLong();
        }
        Assertions.assertEquals(2L, totalHourlyPass, "Total hourly pass count must match known items");
        Assertions.assertEquals(2L, totalHourlyReject, "Total hourly reject count must match known items");
    }

    // 8. GET /api/inspections filtering and paging
    @Test
    void inspectionsFilteringAndPaging() throws Exception {
        String admin = token("admin", "Admin@123");

        // Insert distinct inspection records
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "filter-cap-pass-1", "cap", new String[]{}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "filter-cap-pass-2", "cap", new String[]{}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("REJECT", "filter-cap-reject", "cap", new String[]{"dent"}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("REJECT", "filter-card-reject", "card", new String[]{"skew"}))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        // Filter by productType="card"
        mvc.perform(get("/api/inspections")
                        .param("productType", "card")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productType").value("card"));

        // Filter by verdict="REJECT"
        mvc.perform(get("/api/inspections")
                        .param("verdict", "REJECT")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Filter by both productType="cap" and verdict="REJECT"
        mvc.perform(get("/api/inspections")
                        .param("productType", "cap")
                        .param("verdict", "REJECT")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].trackId").value("filter-cap-reject"));

        // Paging: page 0, size 2
        mvc.perform(get("/api/inspections")
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Paging: page 1, size 2
        mvc.perform(get("/api/inspections")
                        .param("page", "1")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // 9. Admin user management CRUD & role checks
    @Test
    void adminUserManagementCrud() throws Exception {
        String admin = token("admin", "Admin@123");
        String operator = token("operator", "Oper@123");

        // Non-admin gets 403 Forbidden
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isForbidden());

        // Admin lists users (200 OK)
        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'admin')]").exists());

        // Admin creates a new user
        String createResponse = mvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newworker\",\"password\":\"Worker@123\",\"role\":\"OPERATOR\",\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newworker"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();

        long workerId = mapper.readTree(createResponse).get("id").asLong();

        // New user can log in
        String workerToken = token("newworker", "Worker@123");
        Assertions.assertNotNull(workerToken);

        // Admin updates user (disables user)
        mvc.perform(put("/api/admin/users/" + workerId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERVISOR\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.enabled").value(false));

        // Disabled user cannot log in
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newworker\",\"password\":\"Worker@123\"}"))
                .andExpect(status().isUnauthorized());

        // Admin deletes user
        mvc.perform(delete("/api/admin/users/" + workerId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // Deleted user cannot log in
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newworker\",\"password\":\"Worker@123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // 10. Device posting restrictions
    @Test
    void deviceCanPostButCannotReadLinesAndMissingKeyIsUnauthorized() throws Exception {
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "test-no-key")))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "test-device"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/lines")
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isForbidden());
    }

    // 11. Live stats & search
    @Test
    void postedInspectionAppearsInSearchAndStatsAndRateIsCorrect() throws Exception {
        mvc.perform(multipart("/api/inspections")
                        .file(result("PASS", "stats-pass"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/inspections")
                        .file(result("REJECT", "stats-reject"))
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        String admin = token("admin", "Admin@123");
        mvc.perform(get("/api/inspections")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trackId").exists());

        mvc.perform(get("/api/stats/live")
                        .param("minutes", "120")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(1))
                .andExpect(jsonPath("$.reject").value(1))
                .andExpect(jsonPath("$.defectRate").value(0.5));
    }
}
