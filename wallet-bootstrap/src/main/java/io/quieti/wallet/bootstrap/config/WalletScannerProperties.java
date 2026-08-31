package io.quieti.wallet.bootstrap.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("wallet.scanners")
public record WalletScannerProperties(
    boolean shadowMode,
    @Valid @NotNull Bitcoin btc,
    @Valid @NotNull Evm evm,
    @Valid @NotNull Solana solana,
    @Valid @NotNull Ton ton) {

    public record Bitcoin(
            boolean enabled,
            @NotBlank String network,
            @NotBlank String scannerId,
            @NotNull Duration pollInterval,
            @Min(1) long maxReorgDepth,
            @Min(0) int maxCycleRetries,
            @Min(1) long confirmations) {

        public Bitcoin {
            if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
                throw new IllegalArgumentException("wallet.scanners.btc.poll-interval must be positive");
            }
            if (network != null && !network.equals("BTC_TESTNET") && !network.equals("BTC_SIGNET")) {
                throw new IllegalArgumentException(
                        "wallet.scanners.btc.network must be BTC_TESTNET or BTC_SIGNET");
            }
        }
    }

    public record Evm(
            boolean enabled,
            @Min(1) long chainId,
            @NotBlank String scannerId,
            @NotNull Duration pollInterval,
            @Min(1) long maxReorgDepth,
            @Min(0) int maxCycleRetries,
            @NotBlank String observationHead,
            @NotBlank String finalityHead,
            @Min(0) long fallbackConfirmations,
            boolean includeInternal,
            @NotEmpty List<@NotBlank String> providerKeys,
            Set<String> tokenContracts) {

        public Evm {
            providerKeys = providerKeys == null ? List.of() : List.copyOf(providerKeys);
            tokenContracts = tokenContracts == null ? null : Set.copyOf(tokenContracts);
            if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
                throw new IllegalArgumentException("wallet.scanners.evm.poll-interval must be positive");
            }
            validateHead(observationHead, "observation-head");
            validateHead(finalityHead, "finality-head");
        }

        private static void validateHead(String value, String property) {
            if (value != null
                    && !value.equals("latest")
                    && !value.equals("safe")
                    && !value.equals("finalized")) {
                throw new IllegalArgumentException(
                        "wallet.scanners.evm." + property + " must be latest, safe, or finalized");
            }
        }
    }

    public record Solana(
            boolean enabled,
            @NotBlank String genesisHash,
            @NotBlank String scannerId,
            @NotNull Duration pollInterval,
            @Min(1) long maxReorgDepth,
            @Min(0) int maxCycleRetries,
            @Min(1) long maxSlotLag,
            @NotNull Duration baseBackoff,
            @NotNull Duration maxBackoff,
            @NotEmpty List<@NotBlank String> providerKeys,
            @NotNull List<@Valid SolanaAsset> assets,
            @NotNull Map<String, String> tokenAccounts) {

        public Solana {
            providerKeys = providerKeys == null ? List.of() : List.copyOf(providerKeys);
            assets = assets == null ? List.of() : List.copyOf(assets);
            tokenAccounts = tokenAccounts == null ? Map.of() : Map.copyOf(tokenAccounts);
            requirePositive(pollInterval, "poll-interval");
            requirePositive(baseBackoff, "base-backoff");
            requirePositive(maxBackoff, "max-backoff");
            if (baseBackoff != null && maxBackoff != null && maxBackoff.compareTo(baseBackoff) < 0) {
                throw new IllegalArgumentException(
                        "wallet.scanners.solana.max-backoff must not be shorter than base-backoff");
            }
        }

        private static void requirePositive(Duration value, String property) {
            if (value != null && (value.isZero() || value.isNegative())) {
                throw new IllegalArgumentException(
                        "wallet.scanners.solana." + property + " must be positive");
            }
        }
    }

    public record SolanaAsset(
            @NotBlank String programId,
            @NotBlank String mint,
            @Min(0) int decimals) {
    }

    public record Ton(
            boolean enabled,
            @NotBlank String network,
            @NotBlank String scannerId,
            @NotNull Duration pollInterval,
            @Min(1) long maxReorgDepth,
            @Min(0) int maxCycleRetries,
            @Min(0) long observationDepth,
            @Min(1) long finalityDepth,
            @NotBlank String providerKey,
            @NotNull List<@Valid TonJetton> jettons) {

        public Ton {
            jettons = jettons == null ? List.of() : List.copyOf(jettons);
            if (network != null && !Set.of("mainnet", "testnet").contains(network)) {
                throw new IllegalArgumentException("wallet.scanners.ton.network must be mainnet or testnet");
            }
            if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
                throw new IllegalArgumentException("wallet.scanners.ton.poll-interval must be positive");
            }
            if (finalityDepth < observationDepth) {
                throw new IllegalArgumentException(
                        "wallet.scanners.ton.finality-depth must not be less than observation-depth");
            }
        }
    }

    public record TonJetton(
            @NotBlank String master,
            @Min(0) int decimals) {
    }
}