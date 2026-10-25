package com.qualiscan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.*;
import com.qualiscan.repo.AppUserRepository;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.repo.ShiftSummaryRepository;
import com.qualiscan.security.Crypto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DateRangeAndShiftReportTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired ProductionLineRepository lineRepository;
    @Autowired ShiftSummaryRepository shiftSummaryRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired PasswordEncoder encoder;

    private ProductionLine testLine;
    private String supervisorToken;

    @BeforeEach
    void setUp() throws Exception {
        shiftSummaryRepository.deleteAll();
        inspectionRepository.deleteAll();

        userRepository.findByUsername("supervisor").orElseGet(() ->
                userRepository.save(new AppUser("supervisor", encoder.encode("Super@123"), Role.SUPERVISOR)));

        String loginRes = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"supervisor\",\"password\":\"Super@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        supervisorToken = mapper.readTree(loginRes).get("token").asText();

        String hash = Crypto.sha256("date-range-test-key");
        testLine = lineRepository.findAll().stream()
                .filter(l -> "Line 1".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lineRepository.save(new ProductionLine("Line 1", hash)));
        testLine.setActiveBatchId(null);
        testLine = lineRepository.save(testLine);
    }

    @Test
    void testDateRangeFilteringAndShiftReportKnownData() throws Exception {
        // Known data for 2026-10-24
        Instant from = Instant.parse("2026-10-24T00:00:00Z");
        Instant to = Instant.parse("2099-01-01T00:00:00Z");

        // 1. In-range inspection: cap PASS at 06:00
        inspectionRepository.save(new InspectionResult(testLine, null, "track-1", "cap", 0.99, Verdict.PASS, "", 100, 0.8, 1.0, null, Instant.parse("2026-10-24T06:00:00Z")));
        // 2. In-range inspection: cap REJECT (scratch, dent) at 08:00
        inspectionRepository.save(new InspectionResult(testLine, null, "track-2", "cap", 0.95, Verdict.REJECT, "scratch,dent", 102, 0.75, 1.05, null, Instant.parse("2026-10-24T08:00:00Z")));
        // 3. In-range inspection: card REJECT (dent) at 10:00
        inspectionRepository.save(new InspectionResult(testLine, null, "track-3", "card", 0.96, Verdict.REJECT, "dent", 150, 0.6, 1.5, null, Instant.parse("2026-10-24T10:00:00Z")));
        // 4. In-range inspection: card UNKNOWN at 11:00
        inspectionRepository.save(new InspectionResult(testLine, null, "track-4", "card", 0.50, Verdict.UNKNOWN, "", 120, 0.7, 1.2, null, Instant.parse("2026-10-24T11:00:00Z")));
        // 5. In-range boundary inspection: cap PASS at exactly from (00:00:00Z) -> INCLUSIVE
        inspectionRepository.save(new InspectionResult(testLine, null, "track-5", "cap", 0.99, Verdict.PASS, "", 101, 0.8, 1.0, null, from));

        // 6. Out-of-range boundary inspection: cap PASS at exactly to (2099-01-01T00:00:00Z) -> EXCLUSIVE
        inspectionRepository.save(new InspectionResult(testLine, null, "track-6-out", "cap", 0.99, Verdict.PASS, "", 100, 0.8, 1.0, null, to));
        // 7. Out-of-range before from inspection: cap PASS at 2026-10-23T23:59:59Z -> EXCLUDED
        inspectionRepository.save(new InspectionResult(testLine, null, "track-7-out", "cap", 0.99, Verdict.PASS, "", 100, 0.8, 1.0, null, Instant.parse("2026-10-23T23:59:59Z")));

        Long lineId = testLine.getId();

        // Step 1 check: Unfiltered search returns all 7 rows
        mvc.perform(get("/api/inspections")
                        .param("lineId", String.valueOf(lineId))
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7));

        // Step 1 assert: GET /api/inspections with from and to returns exactly the 5 in-range rows (from inclusive, to exclusive)
        mvc.perform(get("/api/inspections")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));

        // Combined filter assert: lineId + from + to + productType="cap" + verdict="REJECT" -> exactly 1 row ("track-2")
        mvc.perform(get("/api/inspections")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("productType", "cap")
                        .param("verdict", "REJECT")
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].trackId").value("track-2"));

        // Step 1 assert: POST /api/reports/shift for the same range returns exact numbers:
        // total: 5, pass: 2, reject: 2, unknown: 1
        // defectRate = 2 / (2 + 2) = 0.50 (UNKNOWN excluded from denominator)
        // Since 'to' (2099-01-01T00:00:00Z) is in the future, it must return partial: true, id: null, and NOT persist
        String reportRes = mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftName").value("Custom 2026-10-24T05:30:00+05:30 to 2099-01-01T05:30:00+05:30"))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.pass").value(2))
                .andExpect(jsonPath("$.reject").value(2))
                .andExpect(jsonPath("$.unknown").value(1))
                .andExpect(jsonPath("$.defectRate").value(0.50))
                .andReturn().getResponse().getContentAsString();

        JsonNode reportNode = mapper.readTree(reportRes);
        Assertions.assertTrue(reportNode.get("id").isNull(), "Future-ending shift report must not have an assigned id");

        // Must NOT be persisted because 'to' is in the future
        Assertions.assertEquals(0, shiftSummaryRepository.count(),
                "Shift reports for future periods must not be persisted (no stored row)");

        // Repeated call for future-ending period also returns no stored row and partial: true
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true));
        Assertions.assertEquals(0, shiftSummaryRepository.count(),
                "Repeated calls for future-ending period must not store rows");

        String byProductJson = reportNode.get("byProductJson").asText();
        String defectsByTypeJson = reportNode.get("defectsByTypeJson").asText();

        JsonNode byProduct = mapper.readTree(byProductJson);
        Assertions.assertTrue(byProduct.has("cap"), "byProductJson must contain cap");
        Assertions.assertEquals(3, byProduct.get("cap").get("total").asInt());
        Assertions.assertEquals(2, byProduct.get("cap").get("pass").asInt());
        Assertions.assertEquals(1, byProduct.get("cap").get("reject").asInt());

        Assertions.assertTrue(byProduct.has("card"), "byProductJson must contain card");
        Assertions.assertEquals(2, byProduct.get("card").get("total").asInt());
        Assertions.assertEquals(1, byProduct.get("card").get("reject").asInt());
        Assertions.assertEquals(1, byProduct.get("card").get("unknown").asInt());

        JsonNode defectsByType = mapper.readTree(defectsByTypeJson);
        Assertions.assertEquals(2, defectsByType.get("dent").asInt());
        Assertions.assertEquals(1, defectsByType.get("scratch").asInt());

        // Past range: persisted and reused (same id on repeated calls)
        Instant pastFrom = Instant.parse("2026-10-24T06:00:00Z");
        Instant pastTo = Instant.parse("2026-10-24T09:00:00Z"); // In the past relative to current time
        String pastRes1 = mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", pastFrom.toString())
                        .param("to", pastTo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.shiftName").value("Custom 2026-10-24T11:30:00+05:30 to 2026-10-24T14:30:00+05:30"))
                .andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString();

        Assertions.assertEquals(1, shiftSummaryRepository.count(),
                "Shift report for past period must be persisted");
        Long savedId = mapper.readTree(pastRes1).get("id").asLong();

        // Idempotent reuse: second call returns the exact same ID
        String pastRes2 = mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", pastFrom.toString())
                        .param("to", pastTo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedId))
                .andExpect(jsonPath("$.partial").value(false))
                .andReturn().getResponse().getContentAsString();

        Assertions.assertEquals(savedId, mapper.readTree(pastRes2).get("id").asLong(),
                "Repeated call for ended period must return the exact same ID");
        Assertions.assertEquals(1, shiftSummaryRepository.count(),
                "Second call must reuse persisted record without creating duplicates");
    }

    @Test
    void testConfiguredShiftWindowsNaming() throws Exception {
        Long lineId = testLine.getId();

        // Asia/Kolkata is UTC+05:30.
        // Shift A: 06:00 to 14:00 IST -> 00:30 UTC to 08:30 UTC
        Instant shiftAFrom = Instant.parse("2026-10-24T00:30:00Z");
        Instant shiftATo = Instant.parse("2026-10-24T08:30:00Z");
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", shiftAFrom.toString())
                        .param("to", shiftATo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftName").value("Shift A"));

        // Shift B: 14:00 to 22:00 IST -> 08:30 UTC to 16:30 UTC
        Instant shiftBFrom = Instant.parse("2026-10-24T08:30:00Z");
        Instant shiftBTo = Instant.parse("2026-10-24T16:30:00Z");
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", shiftBFrom.toString())
                        .param("to", shiftBTo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftName").value("Shift B"));

        // Shift C: 22:00 to 06:00 IST -> 16:30 UTC to 00:30 UTC next day
        Instant shiftCFrom = Instant.parse("2026-10-24T16:30:00Z");
        Instant shiftCTo = Instant.parse("2026-10-25T00:30:00Z");
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", shiftCFrom.toString())
                        .param("to", shiftCTo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftName").value("Shift C"));

        // Custom range spanning 5:00 to 20:00 IST (23:30Z to 14:30Z) -> full ISO in shift zone
        Instant customFrom = Instant.parse("2026-10-23T23:30:00Z");
        Instant customTo = Instant.parse("2026-10-24T14:30:00Z");
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(lineId))
                        .param("from", customFrom.toString())
                        .param("to", customTo.toString())
                        .header("Authorization", "Bearer " + supervisorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftName").value("Custom 2026-10-24T05:00:00+05:30 to 2026-10-24T20:00:00+05:30"));
    }

    @Test
    void testUniqueConstraintEnforcedOnLineAndPeriod() {
        Instant from = Instant.parse("2026-10-24T06:00:00Z");
        Instant to = Instant.parse("2026-10-24T09:00:00Z");

        ShiftSummary s1 = new ShiftSummary(testLine, "Shift 1", from, to, 10, 8, 2, 0, 0.2, "{}", "{}");
        shiftSummaryRepository.saveAndFlush(s1);

        ShiftSummary s2 = new ShiftSummary(testLine, "Shift 2", from, to, 10, 8, 2, 0, 0.2, "{}", "{}");
        Assertions.assertThrows(Exception.class, () -> {
            shiftSummaryRepository.saveAndFlush(s2);
        }, "Inserting duplicate ShiftSummary with identical (line, periodStart, periodEnd) must violate unique constraint");
    }
}
