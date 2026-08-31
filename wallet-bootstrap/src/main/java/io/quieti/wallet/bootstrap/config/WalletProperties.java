package io.quieti.wallet.bootstrap.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("wallet")
public record WalletProperties(@Valid @NotNull Scan scan) {

    public record Scan(
            @NotBlank String ownerId,
            @NotNull Duration leaseDuration,
            @Min(1) int threads,
            @Min(1) int queueCapacity) {

        public Scan {
            if (leaseDuration != null && (leaseDuration.isZero() || leaseDuration.isNegative())) {
                throw new IllegalArgumentException("wallet.scan.lease-duration must be positive");
            }
        }
    }
}