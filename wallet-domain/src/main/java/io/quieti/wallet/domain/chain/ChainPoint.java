package io.quieti.wallet.domain.chain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ChainPoint(
        String scope,
        String position,
        String hash,
        List<PointRef> parents,
        Instant time) {

    public ChainPoint {
        scope = Objects.requireNonNullElse(scope, "");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(hash, "hash");
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        if (position.isEmpty() || hash.isEmpty()) {
            throw new IllegalArgumentException("chain point position and hash are required");
        }
        Set<PointRef> uniqueParents = new HashSet<>(parents);
        if (uniqueParents.size() != parents.size()) {
            throw new IllegalArgumentException("duplicate parent");
        }
    }
}