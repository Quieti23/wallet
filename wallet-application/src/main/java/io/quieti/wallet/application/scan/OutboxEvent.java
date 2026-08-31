package io.quieti.wallet.application.scan;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record OutboxEvent(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        byte[] payload,
        Instant occurredAt) {

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (id.isEmpty() || aggregateType.isEmpty() || aggregateId.isEmpty() || eventType.isEmpty()) {
            throw new IllegalArgumentException("outbox identifiers are required");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}