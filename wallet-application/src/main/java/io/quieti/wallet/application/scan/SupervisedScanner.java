package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;
import java.time.Duration;
import java.util.Objects;

public record SupervisedScanner(
        ChainRef chain,
        String scannerId,
        Duration pollInterval,
        ScanCycleRunner runner,
        AutoCloseable runtime) {

    public SupervisedScanner {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(runtime, "runtime");
        if (scannerId.isEmpty()) {
            throw new IllegalArgumentException("scanner ID is required");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        if (runtime instanceof ChainScanAdapter adapter && !adapter.chain().equals(chain)) {
            throw new IllegalArgumentException("runtime and supervised chain differ");
        }
    }
}