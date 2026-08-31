package io.quieti.wallet.domain.chain;

import java.util.Objects;

public record CanonicalUnit(ChainRef chain, ChainPoint point, boolean canonical) {

    public CanonicalUnit {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(point, "point");
    }
}