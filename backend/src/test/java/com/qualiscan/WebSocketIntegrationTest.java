package com.qualiscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualiscan.domain.AppUser;
import com.qualiscan.domain.ProductionLine;
import com.qualiscan.domain.Role;
import com.qualiscan.repo.AppUserRepository;
import com.qualiscan.repo.ProductionLineRepository;
import com.qualiscan.security.Crypto;
import com.qualiscan.service.RealtimePublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.concurrent.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WebSocketIntegrationTest {

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired ProductionLineRepository lines;
    @Autowired PasswordEncoder encoder;

    private String deviceKey;
    private ProductionLine testLine;
    private String userJwt;

    @BeforeEach
    void setUp() throws Exception {
        users.findByUsername("wsuser").orElseGet(() ->
                users.save(new AppUser("wsuser", encoder.encode("WsPass@123"), Role.OPERATOR)));

        String loginRes = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"wsuser\",\"password\":\"WsPass@123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        userJwt = mapper.readTree(loginRes).get("token").asText();

        deviceKey = "ws-test-device-key-1234567890";
        String hash = Crypto.sha256(deviceKey);
        testLine = lines.findAll().stream()
                .filter(l -> "WS Test Line".equalsIgnoreCase(l.getName()))
                .findFirst()
                .orElseGet(() -> lines.save(new ProductionLine("WS Test Line", hash)));
        testLine.setApiKeyHash(hash);
        testLine = lines.save(testLine);
    }

    @Test
    void webSocketConnectWithJwtSubscribesAndReceivesBroadcastedInspection() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        stompClient.setMessageConverter(converter);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + userJwt);

        BlockingQueue<RealtimePublisher.InspectionMessage> queue = new LinkedBlockingQueue<>();

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(session.isConnected(), "STOMP session should be connected");

        // Subscribe to /topic/lines/{id}/inspections
        session.subscribe("/topic/lines/" + testLine.getId() + "/inspections", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return RealtimePublisher.InspectionMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((RealtimePublisher.InspectionMessage) payload);
            }
        });

        // Give subscription a moment to register on broker
        Thread.sleep(300);

        // POST inspection with device key
        String payloadJson = mapper.writeValueAsString(new BackendApiTest.Payload(
                "ws-track-123",
                "cap",
                0.98,
                "PASS",
                new String[]{},
                100.0,
                0.8,
                1.1,
                Instant.now().toString()
        ));
        MockMultipartFile resultPart = new MockMultipartFile("result", "result.json", "application/json", payloadJson.getBytes());

        mvc.perform(multipart("/api/inspections")
                        .file(resultPart)
                        .header("X-API-Key", deviceKey))
                .andExpect(status().isCreated());

        // Assert message arrives within 5 seconds
        RealtimePublisher.InspectionMessage message = queue.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message, "Inspection message should arrive via WebSocket within 5 seconds");
        Assertions.assertEquals("ws-track-123", message.trackId());
        Assertions.assertEquals("cap", message.productType());
        Assertions.assertEquals("PASS", message.verdict());

        session.disconnect();
    }

    @Test
    void webSocketConnectionWithoutJwtOrWithDeviceKeyMustFail() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // 1. Missing JWT connection attempt must fail
        StompHeaders noAuthHeaders = new StompHeaders();
        Assertions.assertThrows(Exception.class, () -> {
            stompClient.connectAsync(
                    "ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(),
                    noAuthHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(4, TimeUnit.SECONDS);
        }, "Connecting without JWT must fail");

        // 2. Connection with device key header must fail
        StompHeaders deviceHeaders = new StompHeaders();
        deviceHeaders.add("X-API-Key", deviceKey);
        Assertions.assertThrows(Exception.class, () -> {
            stompClient.connectAsync(
                    "ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(),
                    deviceHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(4, TimeUnit.SECONDS);
        }, "Connecting with device key must fail");
    }
}
