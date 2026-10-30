package com.muse.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String storagePath,
        boolean cookieSecure,
        boolean simulationEnabled,
        @Min(0) @Max(30000) int defaultStepDelayMs,
        @Min(1) @Max(10000) int taskQueueCapacity) {
}
