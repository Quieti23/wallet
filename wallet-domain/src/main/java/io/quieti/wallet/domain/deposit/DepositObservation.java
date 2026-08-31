package io.quieti.wallet.domain.deposit;

import io.quieti.wallet.domain.chain.Amount;
import io.quieti.wallet.domain.chain.AssetRef;
import io.quieti.wallet.domain.chain.ChainPoint;
import java.util.Map;
import java.util.Objects;

public record DepositObservation(
        ObservationId id,
        AssetRef asset,
        String from,
        String to,
        Amount amount,
        ChainPoint inclusion,
        Map<String, String> metadata) {

    public DepositObservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(asset, "asset");
        from = Objects.requireNonNullElse(from, "");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(inclusion, "inclusion");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (!asset.chain().equals(id.chain())) {
            throw new IllegalArgumentException("asset and observation chains differ");
        }
        if (to.isEmpty()) {
            throw new IllegalArgumentException("destination is required");
        }
    }
}