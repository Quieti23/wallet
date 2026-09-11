package io.quieti.wallet.application.transfer;

import java.math.BigInteger;
import java.util.Objects;

public record TransferRequest(String chain, String toAddress, BigInteger amountAtomicUnits) {

    public TransferRequest {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(toAddress, "toAddress");
        Objects.requireNonNull(amountAtomicUnits, "amountAtomicUnits");
        if (chain.isBlank() || toAddress.isBlank()) {
            throw new IllegalArgumentException("chain and destination address must not be blank");
        }
        if (amountAtomicUnits.signum() <= 0) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }
    }
}