package io.quieti.wallet.application.scan;

import java.util.Objects;

public record FinalityTarget(String kind, String value) {

    public FinalityTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (kind.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("finality target kind and value are required");
        }
    }
}