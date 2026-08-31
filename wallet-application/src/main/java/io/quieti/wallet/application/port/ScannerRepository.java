package io.quieti.wallet.application.port;

import io.quieti.wallet.application.scan.CommitRequest;
import io.quieti.wallet.application.scan.FinalityRequest;
import io.quieti.wallet.application.scan.RollbackRequest;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.domain.chain.ChainRef;

public interface ScannerRepository {

    ScanCursor loadCursor(ChainRef chain, String scannerId);

    WatchSnapshot loadWatchSnapshot(ChainRef chain);

    void commit(CommitRequest request);

    void rollback(RollbackRequest request);

    void advanceFinality(FinalityRequest request);
}