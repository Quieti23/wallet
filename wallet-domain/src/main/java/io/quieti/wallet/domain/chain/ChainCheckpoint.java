package io.quieti.wallet.domain.chain;

import java.util.Objects;

public record ChainCheckpoint(long height, String blockHash) {

    public ChainCheckpoint {
        if (height < 0) {
            throw new IllegalArgumentException("height must not be negative");
        }
        Objects.requireNonNull(blockHash, "blockHash");
        if (blockHash.isBlank()) {
            throw new IllegalArgumentException("blockHash must not be blank");
        }
    }
}