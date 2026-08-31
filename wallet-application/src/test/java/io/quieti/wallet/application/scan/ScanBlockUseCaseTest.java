package io.quieti.wallet.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ScanLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ScanBlockUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void commitsOnlyTheBlockExtendingTheCheckpoint() {
        ScanLease lease = lease();
        NodePort node = (chain, height) -> new ChainBlock(chain, height, "hash-11", "hash-10");
        RecordingRepository repository = new RecordingRepository(lease, true);
        ScanBlockUseCase useCase = useCase(node, repository);

        ChainBlock block = useCase.scanNext("evm-main", "EVM", "worker-1");

        assertEquals(11, block.height());
        assertEquals(block, repository.committed);
    }

    @Test
    void rejectsAReorganizedBlockBeforeCommit() {
        NodePort node = (chain, height) -> new ChainBlock(chain, height, "hash-11b", "other-parent");
        RecordingRepository repository = new RecordingRepository(lease(), true);

        assertThrows(ChainContinuityException.class,
                () -> useCase(node, repository).scanNext("evm-main", "EVM", "worker-1"));
        assertEquals(null, repository.committed);
    }

    @Test
    void rejectsCommitAfterFencingTokenIsLost() {
        NodePort node = (chain, height) -> new ChainBlock(chain, height, "hash-11", "hash-10");

        assertThrows(LeaseLostException.class,
                () -> useCase(node, new RecordingRepository(lease(), false))
                        .scanNext("evm-main", "EVM", "worker-1"));
    }

    private static ScanBlockUseCase useCase(NodePort node, ScanPartitionRepository repository) {
        return new ScanBlockUseCase(
                node,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static ScanLease lease() {
        return new ScanLease(
                "evm-main",
                "EVM",
                "worker-1",
                7,
                NOW.plusSeconds(30),
                new ChainCheckpoint(10, "hash-10"));
    }

    private static final class RecordingRepository implements ScanPartitionRepository {
        private final ScanLease lease;
        private final boolean commitResult;
        private ChainBlock committed;

        private RecordingRepository(ScanLease lease, boolean commitResult) {
            this.lease = lease;
            this.commitResult = commitResult;
        }

        @Override
        public ScanLease acquire(String partitionId, String chain, String ownerId, Instant now, Duration duration) {
            return lease;
        }

        @Override
        public boolean commit(ScanLease lease, ChainBlock block, Instant now) {
            committed = block;
            return commitResult;
        }
    }
}