package io.quieti.wallet.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quieti.wallet.application.port.ScannerRepository;
import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanCoordinatorTest {

    private static final ChainRef CHAIN = ChainRef.parse("solana:devnet");
    private static final FinalityPolicy POLICY = new FinalityPolicy() {
        @Override
        public FinalityTarget observationTarget(ChainRef chain) {
            return new FinalityTarget("commitment", "confirmed");
        }

        @Override
        public FinalityTarget finalityTarget(ChainRef chain) {
            return new FinalityTarget("commitment", "finalized");
        }
    };

    @Test
    void scansAllAvailableBatchesThenAdvancesFinality() {
        RecordingRepository repository = new RecordingRepository(cursor(null, 0));
        RecordingAdapter adapter = new RecordingAdapter(List.of(point(1), point(2)));

        coordinator(adapter, repository).cycle();

        assertEquals(List.of(0L, 1L), repository.commitVersions);
        assertEquals(3, repository.cursor.version());
        assertEquals(2, repository.finalityRequest.expectedVersion());
        assertEquals(3, adapter.scanCalls);
    }

    @Test
    void reloadsStateAfterRollbackBeforeContinuing() {
        RecordingRepository repository = new RecordingRepository(cursor(point(8), 4));
        RecordingAdapter adapter = new RecordingAdapter(List.of());
        adapter.canonical = false;
        adapter.ancestor = point(5);

        coordinator(adapter, repository).cycle();

        assertEquals(1, repository.rollbackCount);
        assertEquals(2, repository.loadCount);
        assertEquals(point(5), repository.cursor.point());
        assertEquals(5, repository.finalityRequest.expectedVersion());
    }

    @Test
    void retriesOnlyStaleCursorFailures() {
        RecordingRepository repository = new RecordingRepository(cursor(null, 0));
        repository.staleCommits = 1;
        RecordingAdapter adapter = new RecordingAdapter(List.of(point(1)));

        coordinator(adapter, repository).cycle();

        assertEquals(2, repository.loadCount);
        assertEquals(1, repository.commitVersions.size());
        assertEquals(1, repository.finalityRequest.expectedVersion());
    }

    private static ScanCoordinator coordinator(
            RecordingAdapter adapter, RecordingRepository repository) {
        return new ScanCoordinator(
                new ScanCoordinatorConfig("deposit", 64, 3),
                adapter,
                repository,
                POLICY);
    }

    private static ScanCursor cursor(ChainPoint point, long version) {
        return new ScanCursor(CHAIN, "deposit", point, version);
    }

    private static ChainPoint point(long position) {
        return new ChainPoint("", Long.toString(position), "hash-" + position, List.of(), null);
    }

    private static final class RecordingAdapter implements ChainScanAdapter {
        private final List<ChainPoint> points;
        private boolean canonical = true;
        private ChainPoint ancestor;
        private int scanCalls;

        private RecordingAdapter(List<ChainPoint> points) {
            this.points = points;
        }

        @Override
        public ChainRef chain() {
            return CHAIN;
        }

        @Override
        public ChainPoint observationHead(FinalityPolicy policy) {
            return point(20);
        }

        @Override
        public CursorCheck verifyCursor(ScanCursor cursor) {
            boolean result = canonical;
            canonical = true;
            return new CursorCheck(result, result ? "" : "reorg");
        }

        @Override
        public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
            scanCalls++;
            int index = Math.toIntExact(cursor.version());
            if (index >= points.size()) {
                return new ScanBatch(List.of(), List.of(), null, false);
            }
            ChainPoint next = points.get(index);
            return new ScanBatch(
                    List.of(new CanonicalUnit(CHAIN, next, true)),
                    List.of(),
                    new ScanCursor(CHAIN, cursor.scannerId(), next, cursor.version() + 1),
                    true);
        }

        @Override
        public ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth) {
            return ancestor;
        }

        @Override
        public ChainPoint finalityHead(FinalityPolicy policy) {
            return point(18);
        }
    }

    private static final class RecordingRepository implements ScannerRepository {
        private ScanCursor cursor;
        private final List<Long> commitVersions = new ArrayList<>();
        private FinalityRequest finalityRequest;
        private int staleCommits;
        private int loadCount;
        private int rollbackCount;

        private RecordingRepository(ScanCursor cursor) {
            this.cursor = cursor;
        }

        @Override
        public ScanCursor loadCursor(ChainRef chain, String scannerId) {
            loadCount++;
            return cursor;
        }

        @Override
        public WatchSnapshot loadWatchSnapshot(ChainRef chain) {
            return new WatchSnapshot(0, List.of());
        }

        @Override
        public void commit(CommitRequest request) {
            if (staleCommits-- > 0) {
                throw new StaleCursorException("test commit");
            }
            commitVersions.add(request.expectedVersion());
            cursor = request.batch().nextCursor();
        }

        @Override
        public void rollback(RollbackRequest request) {
            rollbackCount++;
            cursor = new ScanCursor(
                    request.chain(), request.scannerId(), request.ancestor(), request.expectedVersion() + 1);
        }

        @Override
        public void advanceFinality(FinalityRequest request) {
            finalityRequest = request;
            cursor = new ScanCursor(
                    cursor.chain(), cursor.scannerId(), cursor.point(), cursor.version() + 1);
        }
    }
}