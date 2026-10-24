package com.qualiscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.*;
import com.qualiscan.repo.AppUserRepository;
import com.qualiscan.repo.InspectionRepository;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.repo.ShiftSummaryRepository;
import com.qualiscan.security.Crypto;
import com.qualiscan.service.ShiftReportService;
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
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShiftReportTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ShiftReportService shiftReportService;
    @Autowired ShiftSummaryRepository shiftSummaryRepository;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired ProductionLineRepository lineRepository;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    private ProductionLine testLine;

    @BeforeEach
    void setUp() {
        shiftSummaryRepository.deleteAll();
        inspectionRepository.deleteAll();

        users.findByUsername("operator").orElseGet(() ->
                users.save(new AppUser("operator", encoder.encode("Oper@123"), Role.OPERATOR)));
        users.findByUsername("supervisor").orElseGet(() ->
                users.save(new AppUser("supervisor", encoder.encode("Super@123"), Role.SUPERVISOR)));

        String hash = Crypto.sha256("shift-report-key");
        testLine = lineRepository.findAll().stream()
                .filter(l -> "Shift Line".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lineRepository.save(new ProductionLine("Shift Line", hash)));
    }

    private String token(String username, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("token").asText();
    }

    @Test
    void shiftReportServiceComputesExactTotalsAndIsIdempotent() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(8, ChronoUnit.HOURS);
        Instant from = base;
        Instant to = base.plus(8, ChronoUnit.HOURS);

        // Add 4 inspections within the window:
        // 2 PASS (cap), 1 REJECT (cap: scratch, dent), 1 REJECT (card: dent)
        inspectionRepository.save(new InspectionResult(testLine, null, "t1", "cap", 0.99, Verdict.PASS, "", 100, 0.8, 1.0, null, from.plusSeconds(100)));
        inspectionRepository.save(new InspectionResult(testLine, null, "t2", "cap", 0.99, Verdict.PASS, "", 105, 0.8, 1.0, null, from.plusSeconds(200)));
        inspectionRepository.save(new InspectionResult(testLine, null, "t3", "cap", 0.99, Verdict.REJECT, "scratch,dent", 90, 0.7, 1.2, null, from.plusSeconds(300)));
        inspectionRepository.save(new InspectionResult(testLine, null, "t4", "card", 0.99, Verdict.REJECT, "dent", 95, 0.7, 1.2, null, from.plusSeconds(400)));

        // Inspection OUTSIDE the window (must not be counted)
        inspectionRepository.save(new InspectionResult(testLine, null, "t-out", "cap", 0.99, Verdict.PASS, "", 100, 0.8, 1.0, null, from.minusSeconds(10)));

        // 1. Generate summary
        ShiftSummary summary1 = shiftReportService.generate(testLine.getId(), from, to);

        Assertions.assertNotNull(summary1.getId());
        Assertions.assertEquals(4, summary1.getTotal());
        Assertions.assertEquals(2, summary1.getPass());
        Assertions.assertEquals(2, summary1.getReject());
        Assertions.assertEquals(0, summary1.getUnknown());
        Assertions.assertEquals(0.50, summary1.getDefectRate(), 0.001);
        Assertions.assertTrue(summary1.getByProductJson().contains("\"cap\""));
        Assertions.assertTrue(summary1.getByProductJson().contains("\"card\""));
        Assertions.assertTrue(summary1.getDefectsByTypeJson().contains("\"dent\":2"));
        Assertions.assertTrue(summary1.getDefectsByTypeJson().contains("\"scratch\":1"));

        // 2. Calling generate a second time is idempotent: returns existing summary without creating a duplicate
        ShiftSummary summary2 = shiftReportService.generate(testLine.getId(), from, to);
        Assertions.assertEquals(summary1.getId(), summary2.getId());
        Assertions.assertEquals(1, shiftSummaryRepository.count(), "Calling generate twice must not create duplicate records");
    }

    @Test
    void shiftReportEndpointsRbacAndListing() throws Exception {
        String operator = token("operator", "Oper@123");
        String supervisor = token("supervisor", "Super@123");

        Instant from = Instant.now().minus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);

        // OPERATOR cannot trigger shift report generation (403 Forbidden)
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(testLine.getId()))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isForbidden());

        // SUPERVISOR can generate shift report (200 OK)
        mvc.perform(post("/api/reports/shift")
                        .param("lineId", String.valueOf(testLine.getId()))
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.lineId").value(testLine.getId()));

        // Authenticated user can query shift reports
        mvc.perform(get("/api/reports/shifts")
                        .param("lineId", String.valueOf(testLine.getId()))
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
