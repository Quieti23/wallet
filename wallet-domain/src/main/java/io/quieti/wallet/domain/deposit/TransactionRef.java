package io.quieti.wallet.domain.deposit;

import java.util.Objects;

public record TransactionRef(String kind, String value) {

    public TransactionRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (kind.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("transaction kind and value are required");
        }
    }

    @Override
    public String toString() {
        return kind + ":" + value;
    }
}