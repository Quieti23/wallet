package io.quieti.wallet.domain.chain;

import java.util.Objects;

public record ChainBlock(String chain, long height, String hash, String parentHash) {

    public ChainBlock {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(parentHash, "parentHash");
        if (chain.isBlank() || hash.isBlank()) {
            throw new IllegalArgumentException("chain and hash must not be blank");
        }
        if (height < 1) {
            throw new IllegalArgumentException("height must be positive");
        }
    }
}