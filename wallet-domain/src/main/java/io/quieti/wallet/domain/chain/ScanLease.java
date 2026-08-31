package io.quieti.wallet.domain.chain;

import java.time.Instant;
import java.util.Objects;

public record ScanLease(
        String partitionId,
        String chain,
        String ownerId,
        long leaseVersion,
        Instant leaseUntil,
        ChainCheckpoint checkpoint) {

    public ScanLease {
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (partitionId.isBlank() || chain.isBlank() || ownerId.isBlank()) {
            throw new IllegalArgumentException("lease identifiers must not be blank");
        }
        if (leaseVersion < 1) {
            throw new IllegalArgumentException("leaseVersion must be positive");
        }
    }
}