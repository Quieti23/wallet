package io.quieti.wallet.domain.chain;

import java.util.Objects;

public record PointRef(String scope, String position, String hash) {

    public PointRef {
        scope = Objects.requireNonNullElse(scope, "");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(hash, "hash");
        if (position.isEmpty() || hash.isEmpty()) {
            throw new IllegalArgumentException("parent position and hash are required");
        }
    }
}