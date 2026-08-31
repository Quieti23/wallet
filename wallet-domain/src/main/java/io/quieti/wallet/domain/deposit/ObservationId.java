package io.quieti.wallet.domain.deposit;

import io.quieti.wallet.domain.chain.ChainRef;
import java.util.Objects;

public record ObservationId(ChainRef chain, TransactionRef transaction, String source) {

    public ObservationId {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) {
            throw new IllegalArgumentException("source is required");
        }
    }

    @Override
    public String toString() {
        return chain + "/" + transaction + "/" + source;
    }
}