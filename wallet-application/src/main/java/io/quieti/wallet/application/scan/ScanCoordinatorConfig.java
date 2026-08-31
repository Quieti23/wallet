package io.quieti.wallet.application.scan;

import java.util.Objects;

public record ScanCoordinatorConfig(String scannerId, long maxReorgDepth, int maxCycleRetries) {

    public ScanCoordinatorConfig {
        Objects.requireNonNull(scannerId, "scannerId");
        if (scannerId.isEmpty()) {
            throw new IllegalArgumentException("scanner ID is required");
        }
        if (maxReorgDepth <= 0) {
            throw new IllegalArgumentException("max reorg depth must be positive");
        }
        if (maxCycleRetries < 0) {
            throw new IllegalArgumentException("max cycle retries must not be negative");
        }
        if (maxCycleRetries == 0) {
            maxCycleRetries = 3;
        }
    }
}