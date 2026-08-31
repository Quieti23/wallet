package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainPoint;
import java.util.Objects;

public record FinalityEvaluation(ChainPoint observationHead, ChainPoint finalityHead) {

    public FinalityEvaluation {
        Objects.requireNonNull(observationHead, "observationHead");
        Objects.requireNonNull(finalityHead, "finalityHead");
    }
}