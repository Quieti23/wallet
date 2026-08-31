package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Objects;

public record CommitRequest(
        ChainRef chain,
        String scannerId,
        long expectedVersion,
        ScanBatch batch,
        List<OutboxEvent> outbox) {

    public CommitRequest {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(batch, "batch");
        outbox = List.copyOf(Objects.requireNonNull(outbox, "outbox"));
        if (scannerId.isEmpty() || expectedVersion < 0) {
            throw new IllegalArgumentException("invalid commit cursor identity");
        }
        batch.validateAgainst(new ScanCursor(chain, scannerId, null, expectedVersion));
    }
}