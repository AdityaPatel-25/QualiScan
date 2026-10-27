package com.qualiscan;

import com.qualiscan.domain.*;
import com.qualiscan.repo.*;
import com.qualiscan.security.Crypto;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class QualiScanApplication {
    public static void main(String[] args) { SpringApplication.run(QualiScanApplication.class, args); }

    @Bean
    @org.springframework.context.annotation.Profile("!demo")
    CommandLineRunner seed(AppUserRepository users, ProductionLineRepository lines, ProductProfileRepository profiles, PasswordEncoder encoder) {
        return args -> {
            if (users.count() == 0) {
                users.save(new AppUser("admin", encoder.encode("Admin@123"), Role.ADMIN));
                users.save(new AppUser("supervisor", encoder.encode("Super@123"), Role.SUPERVISOR));
                users.save(new AppUser("operator", encoder.encode("Oper@123"), Role.OPERATOR));
                System.out.println("QualiScan seeded users: admin, supervisor, operator");
            }
            if (lines.count() == 0) {
                String plain = Crypto.newApiKey();
                lines.save(new ProductionLine("Line 1", Crypto.sha256(plain)));
                System.out.println("QualiScan Line 1 API key (shown once): " + plain);
            }
            if (profiles.count() == 0) {
                profiles.save(new ProductProfile("cap", "{\"product\":\"cap\",\"_note\":\"PLACEHOLDER thresholds; calibrate with production samples.\",\"min_area\":800,\"max_area\":35000,\"h_range\":[0,180],\"s_range\":[0,255],\"v_range\":[40,255],\"circularity_range\":[0.65,1.05],\"aspect_ratio_range\":[0.8,1.3],\"solidity_range\":[0.85,1.0],\"min_solidity\":0.85,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"dent\"]}"));
                profiles.save(new ProductProfile("card", "{\"product\":\"card\",\"_note\":\"PLACEHOLDER thresholds; calibrate with production samples.\",\"min_area\":800,\"max_area\":35000,\"h_range\":[0,180],\"s_range\":[0,255],\"v_range\":[40,255],\"circularity_range\":[0.1,0.95],\"aspect_ratio_range\":[1.2,2.2],\"solidity_range\":[0.85,1.0],\"max_skew_deg\":8,\"max_diff_ratio\":0.12,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"orientation\",\"template_match\"]}"));
                profiles.save(new ProductProfile("biscuit", "{\"product\":\"biscuit\",\"_note\":\"PLACEHOLDER thresholds; calibrate with production samples.\",\"min_area\":800,\"max_area\":35000,\"h_range\":[0,40],\"s_range\":[20,255],\"v_range\":[40,255],\"circularity_range\":[0.3,1.05],\"aspect_ratio_range\":[0.8,2.2],\"solidity_range\":[0.75,1.0],\"min_solidity\":0.75,\"expected_area_range\":[800,35000],\"max_dark_fraction\":0.15,\"dark_value_threshold\":60,\"enabled_checks\":[\"size\",\"color\",\"shape\",\"broken\",\"burnt\"]}"));
            }
        };
    }
}
