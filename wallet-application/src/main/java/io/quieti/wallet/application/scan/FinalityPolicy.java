package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;

public interface FinalityPolicy {

    FinalityTarget observationTarget(ChainRef chain);

    FinalityTarget finalityTarget(ChainRef chain);
}