package io.quieti.wallet.application.scan;

import java.util.Objects;

public record WatchTarget(String normalizedTarget, String ownerId, String accountId) {

    public WatchTarget {
        Objects.requireNonNull(normalizedTarget, "normalizedTarget");
        Objects.requireNonNull(ownerId, "ownerId");
        accountId = Objects.requireNonNullElse(accountId, "");
        if (normalizedTarget.isEmpty() || ownerId.isEmpty()) {
            throw new IllegalArgumentException("watch target and owner are required");
        }
    }
}