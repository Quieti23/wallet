package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Objects;

public record RollbackRequest(
        ChainRef chain,
        String scannerId,
        long expectedVersion,
        ChainPoint ancestor,
        List<OutboxEvent> outbox) {

    public RollbackRequest {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(ancestor, "ancestor");
        outbox = List.copyOf(Objects.requireNonNull(outbox, "outbox"));
        if (scannerId.isEmpty() || expectedVersion < 0) {
            throw new IllegalArgumentException("invalid rollback cursor identity");
        }
    }
}