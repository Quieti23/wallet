package io.quieti.wallet.adapter.persistence;

import io.quieti.wallet.adapter.node.DeterministicNodeAdapter;
import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ScanLease;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class InMemoryScanPartitionRepository implements ScanPartitionRepository {

    private final Map<String, PartitionState> partitions = new HashMap<>();

    @Override
    public synchronized ScanLease acquire(
            String partitionId, String chain, String ownerId, Instant now, Duration leaseDuration) {
        PartitionState state = partitions.computeIfAbsent(
                partitionId,
                ignored -> new PartitionState(chain, null, 0, Instant.EPOCH,
                        new ChainCheckpoint(0, DeterministicNodeAdapter.hash(chain, 0))));
        if (!state.chain.equals(chain)) {
            throw new IllegalArgumentException("Partition is already assigned to another chain");
        }
        if (state.ownerId != null && state.leaseUntil.isAfter(now) && !state.ownerId.equals(ownerId)) {
            throw new IllegalStateException("Partition lease is held by another owner");
        }
        if (!ownerId.equals(state.ownerId) || !state.leaseUntil.isAfter(now)) {
            state.ownerId = ownerId;
            state.leaseVersion++;
        }
        state.leaseUntil = now.plus(leaseDuration);
        return state.toLease(partitionId);
    }

    @Override
    public synchronized boolean commit(ScanLease lease, ChainBlock block, Instant now) {
        PartitionState state = partitions.get(lease.partitionId());
        if (state == null
                || !lease.ownerId().equals(state.ownerId)
                || lease.leaseVersion() != state.leaseVersion
                || !state.leaseUntil.isAfter(now)
                || block.height() != state.checkpoint.height() + 1
                || !block.parentHash().equals(state.checkpoint.blockHash())) {
            return false;
        }
        state.checkpoint = new ChainCheckpoint(block.height(), block.hash());
        return true;
    }

    private static final class PartitionState {
        private final String chain;
        private String ownerId;
        private long leaseVersion;
        private Instant leaseUntil;
        private ChainCheckpoint checkpoint;

        private PartitionState(
                String chain,
                String ownerId,
                long leaseVersion,
                Instant leaseUntil,
                ChainCheckpoint checkpoint) {
            this.chain = chain;
            this.ownerId = ownerId;
            this.leaseVersion = leaseVersion;
            this.leaseUntil = leaseUntil;
            this.checkpoint = checkpoint;
        }

        private ScanLease toLease(String partitionId) {
            return new ScanLease(partitionId, chain, ownerId, leaseVersion, leaseUntil, checkpoint);
        }
    }
}