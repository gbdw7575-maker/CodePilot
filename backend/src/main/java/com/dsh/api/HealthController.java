package com.dsh.api;

import com.dsh.api.dto.Health;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String contractVersion;

    public HealthController(@Value("${dsh.contract-version}") String contractVersion) {
        this.contractVersion = contractVersion;
    }

    @GetMapping("/health")
    public Health getHealth() {
        return new Health(Health.Status.UP, contractVersion, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
