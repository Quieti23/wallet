package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.deposit.DepositObservation;
import java.util.List;
import java.util.Objects;

public record ScanBatch(
        List<CanonicalUnit> units,
        List<DepositObservation> observations,
        ScanCursor nextCursor,
        boolean progress) {

    public ScanBatch {
        units = List.copyOf(Objects.requireNonNull(units, "units"));
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (progress) {
            Objects.requireNonNull(nextCursor, "nextCursor");
        } else if (!units.isEmpty() || !observations.isEmpty()) {
            throw new IllegalArgumentException("no-progress batch contains data");
        }
    }

    public void validateAgainst(ScanCursor current) {
        Objects.requireNonNull(current, "current");
        if (!progress) {
            return;
        }
        if (!nextCursor.chain().equals(current.chain())
                || !nextCursor.scannerId().equals(current.scannerId())) {
            throw new IllegalArgumentException("next cursor identity changed");
        }
        if (nextCursor.version() != current.version() + 1) {
            throw new IllegalArgumentException("next cursor version must advance by one");
        }
        if (samePoint(current.point(), nextCursor.point())) {
            throw new IllegalArgumentException("adapter reported progress without advancing cursor");
        }
        if (units.stream().anyMatch(unit -> !unit.chain().equals(current.chain()))) {
            throw new IllegalArgumentException("canonical unit chain differs from cursor chain");
        }
        if (observations.stream().anyMatch(observation -> !observation.id().chain().equals(current.chain()))) {
            throw new IllegalArgumentException("observation chain differs from cursor chain");
        }
    }

    private static boolean samePoint(
            io.quieti.wallet.domain.chain.ChainPoint left,
            io.quieti.wallet.domain.chain.ChainPoint right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.scope().equals(right.scope())
                && left.position().equals(right.position())
                && left.hash().equals(right.hash());
    }
}