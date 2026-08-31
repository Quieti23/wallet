package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;

public interface ChainScanAdapter extends AutoCloseable {

    ChainRef chain();

    ChainPoint observationHead(FinalityPolicy policy);

    CursorCheck verifyCursor(ScanCursor cursor);

    ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head);

    ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth);

    ChainPoint finalityHead(FinalityPolicy policy);

    @Override
    default void close() {
    }
}