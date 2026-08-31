package io.quieti.wallet.application.scan;

import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ScanLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ScanBlockUseCase {

    private final NodePort nodePort;
    private final ScanPartitionRepository partitionRepository;
    private final Clock clock;
    private final Duration leaseDuration;

    public ScanBlockUseCase(
            NodePort nodePort,
            ScanPartitionRepository partitionRepository,
            Clock clock,
            Duration leaseDuration) {
        this.nodePort = Objects.requireNonNull(nodePort, "nodePort");
        this.partitionRepository = Objects.requireNonNull(partitionRepository, "partitionRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    public ChainBlock scanNext(String partitionId, String chain, String ownerId) {
        Instant now = clock.instant();
        ScanLease lease = partitionRepository.acquire(partitionId, chain, ownerId, now, leaseDuration);
        long nextHeight = lease.checkpoint().height() + 1;
        ChainBlock block = nodePort.fetchBlock(chain, nextHeight);
        if (!block.parentHash().equals(lease.checkpoint().blockHash())) {
            throw new ChainContinuityException(nextHeight);
        }
        if (!partitionRepository.commit(lease, block, clock.instant())) {
            throw new LeaseLostException(partitionId);
        }
        return block;
    }
}