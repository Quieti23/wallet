package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;
import java.util.Objects;

public record ShadowDiff(
        ChainRef chain,
        String scannerId,
        String kind,
        String position,
        String expected,
        String actual) {

    public ShadowDiff {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
    }
}