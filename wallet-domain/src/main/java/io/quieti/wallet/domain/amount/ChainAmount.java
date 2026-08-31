package io.quieti.wallet.domain.amount;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public record ChainAmount(String assetId, BigInteger atomicUnits) {

    public ChainAmount {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(atomicUnits, "atomicUnits");
        if (assetId.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
        if (atomicUnits.signum() < 0) {
            throw new IllegalArgumentException("atomicUnits must not be negative");
        }
    }

    public static ChainAmount fromDisplay(String assetId, BigDecimal displayAmount, int decimals) {
        Objects.requireNonNull(displayAmount, "displayAmount");
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must not be negative");
        }
        return new ChainAmount(assetId, displayAmount.movePointRight(decimals).toBigIntegerExact());
    }

    public BigDecimal toDisplay(int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must not be negative");
        }
        return new BigDecimal(atomicUnits).movePointLeft(decimals);
    }
}