package io.quieti.wallet.application.transfer;

import java.math.BigInteger;
import java.util.Objects;

public record UnsignedTransaction(
        String chain,
        String format,
        String payload,
        BigInteger feeAtomicUnits,
        int changePosition) {

    public UnsignedTransaction {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(feeAtomicUnits, "feeAtomicUnits");
        if (chain.isBlank() || format.isBlank() || payload.isBlank()) {
            throw new IllegalArgumentException("transaction metadata and payload must not be blank");
        }
        if (feeAtomicUnits.signum() < 0 || changePosition < -1) {
            throw new IllegalArgumentException("fee or change position is invalid");
        }
    }
}