package io.quieti.wallet.application.port;

import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ScanLease;
import java.time.Duration;
import java.time.Instant;

public interface ScanPartitionRepository {

        ScanLease acquire(
            String partitionId,
            String chain,
            String ownerId,
            ChainCheckpoint initialCheckpoint,
            Instant now,
            Duration leaseDuration);

    boolean commit(ScanLease lease, ChainBlock block, Instant now);
}