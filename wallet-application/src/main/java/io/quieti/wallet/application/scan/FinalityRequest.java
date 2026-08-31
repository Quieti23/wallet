package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Objects;

public record FinalityRequest(
        ChainRef chain,
        String scannerId,
        long expectedVersion,
        FinalityEvaluation evaluation,
        List<OutboxEvent> outbox) {

    public FinalityRequest {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(scannerId, "scannerId");
        Objects.requireNonNull(evaluation, "evaluation");
        outbox = List.copyOf(Objects.requireNonNull(outbox, "outbox"));
        if (scannerId.isEmpty() || expectedVersion < 0) {
            throw new IllegalArgumentException("invalid finality cursor identity");
        }
    }
}