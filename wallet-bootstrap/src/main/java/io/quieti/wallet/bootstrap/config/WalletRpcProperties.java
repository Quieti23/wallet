package io.quieti.wallet.bootstrap.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("wallet.rpc")
public record WalletRpcProperties(
        boolean enabled,
        @NotNull Duration connectTimeout,
        @NotNull Duration requestTimeout,
        @Min(1) int maxResponseBytes,
        @Valid Map<String, Endpoint> chains) {

    public WalletRpcProperties {
        chains = chains == null ? Map.of() : Map.copyOf(chains);
        if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
            throw new IllegalArgumentException("wallet.rpc.connect-timeout must be positive");
        }
        if (requestTimeout != null && (requestTimeout.isZero() || requestTimeout.isNegative())) {
            throw new IllegalArgumentException("wallet.rpc.request-timeout must be positive");
        }
    }

    public record Endpoint(
            @NotNull URI endpoint,
            @Min(0) long startHeight,
            @NotNull String startHash,
            String username,
            String password,
            String apiKey,
            @Min(0) int maxSkippedPositions) {
    }
}