package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainRef;

public interface ShadowStateReader {

    ScanCursor loadCursor(ChainRef chain, String scannerId);

    WatchSnapshot loadWatchSnapshot(ChainRef chain);
}