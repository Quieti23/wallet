package io.quieti.wallet.application.scan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record WatchSnapshot(long version, List<WatchTarget> targets) {

    public WatchSnapshot {
        if (version < 0) {
            throw new IllegalArgumentException("watch version must not be negative");
        }
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        HashSet<String> uniqueTargets = new HashSet<>();
        for (WatchTarget target : targets) {
            if (!uniqueTargets.add(target.normalizedTarget())) {
                throw new IllegalArgumentException("duplicate watch target: " + target.normalizedTarget());
            }
        }
    }
}