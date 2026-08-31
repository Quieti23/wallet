package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;

@FunctionalInterface
public interface CycleFailureReporter {

    void report(ChainRef chain, String scannerId, RuntimeException failure);
}