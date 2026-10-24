package com.qualiscan.api;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    @GetMapping("/")
    public Map<String, String> status() {
        return Map.of("application", "QualiScan backend", "status", "running");
    }
}
