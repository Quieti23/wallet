package io.quieti.wallet.application.transfer;

import java.math.BigInteger;
import java.util.Objects;

public record BalanceSnapshot(
        String chain,
        String asset,
        BigInteger availableAtomicUnits,
        BigInteger pendingAtomicUnits,
        BigInteger immatureAtomicUnits,
        int decimals) {

    public BalanceSnapshot {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(availableAtomicUnits, "availableAtomicUnits");
        Objects.requireNonNull(pendingAtomicUnits, "pendingAtomicUnits");
        Objects.requireNonNull(immatureAtomicUnits, "immatureAtomicUnits");
        if (chain.isBlank() || asset.isBlank()) {
            throw new IllegalArgumentException("chain and asset must not be blank");
        }
        if (availableAtomicUnits.signum() < 0
                || pendingAtomicUnits.signum() < 0
                || immatureAtomicUnits.signum() < 0
                || decimals < 0) {
            throw new IllegalArgumentException("balance values and decimals must not be negative");
        }
    }
}