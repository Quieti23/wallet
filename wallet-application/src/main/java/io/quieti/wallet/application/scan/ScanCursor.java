package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.Objects;

public record ScanCursor(ChainRef chain, String scannerId, ChainPoint point, long version) {

    public ScanCursor {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        if (scannerId.isEmpty()) {
            throw new IllegalArgumentException("scanner ID is required");
        }
        if (version < 0) {
            throw new IllegalArgumentException("cursor version must not be negative");
        }
    }
}