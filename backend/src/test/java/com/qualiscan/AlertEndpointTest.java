package com.qualiscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.*;
import com.qualiscan.repo.AlertRepository;
import com.qualiscan.repo.AppUserRepository;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.security.Crypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AlertEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AlertRepository alertRepository;
    @Autowired ProductionLineRepository lineRepository;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;

    private ProductionLine testLine;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();

        users.findByUsername("operator").orElseGet(() ->
                users.save(new AppUser("operator", encoder.encode("Oper@123"), Role.OPERATOR)));
        users.findByUsername("supervisor").orElseGet(() ->
                users.save(new AppUser("supervisor", encoder.encode("Super@123"), Role.SUPERVISOR)));

        String hash = Crypto.sha256("alert-api-key");
        testLine = lineRepository.findAll().stream()
                .filter(l -> "Alert Endpoint Line".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lineRepository.save(new ProductionLine("Alert Endpoint Line", hash)));
    }

    private String token(String username, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("token").asText();
    }

    @Test
    void alertAcknowledgeAndResolveRbacAndStatusFilter() throws Exception {
        String operator = token("operator", "Oper@123");
        String supervisor = token("supervisor", "Super@123");

        // Create test alerts
        Alert openAlert = alertRepository.save(new Alert(
                testLine,
                "cap",
                AlertType.HIGH_DEFECT_RATE,
                0.25,
                0.10,
                5,
                30,
                "Test Open Alert"
        ));

        Alert resolvedAlert = alertRepository.save(new Alert(
                testLine,
                "card",
                AlertType.HIGH_DEFECT_RATE,
                0.05,
                0.10,
                5,
                30,
                "Test Resolved Alert"
        ));
        resolvedAlert.resolve();
        alertRepository.save(resolvedAlert);

        // 1. Any logged-in user can query alerts
        mvc.perform(get("/api/alerts")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // 2. Status filter works: filter by OPEN
        mvc.perform(get("/api/alerts")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(openAlert.getId()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));

        // Status filter: filter by RESOLVED
        mvc.perform(get("/api/alerts")
                        .param("status", "RESOLVED")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(resolvedAlert.getId()))
                .andExpect(jsonPath("$.content[0].status").value("RESOLVED"));

        // 3. OPERATOR gets 403 on acknowledge & resolve
        mvc.perform(post("/api/alerts/" + openAlert.getId() + "/acknowledge")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/alerts/" + openAlert.getId() + "/resolve")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isForbidden());

        // 4. SUPERVISOR succeeds on acknowledge
        mvc.perform(post("/api/alerts/" + openAlert.getId() + "/acknowledge")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("supervisor"))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());

        // 5. SUPERVISOR succeeds on resolve
        mvc.perform(post("/api/alerts/" + openAlert.getId() + "/resolve")
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }
}
