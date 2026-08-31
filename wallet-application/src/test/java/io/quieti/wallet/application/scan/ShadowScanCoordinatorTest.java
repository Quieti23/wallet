package io.quieti.wallet.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import io.quieti.wallet.domain.deposit.DepositObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShadowScanCoordinatorTest {

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
    void reportsSemanticDifferencesWithoutRepositoryWrites() {
        ReadOnlyRepository repository = new ReadOnlyRepository();
        RecordingReporter reporter = new RecordingReporter();
        ChainScanAdapter adapter = new ScriptedAdapter(List.of(point(1), point(2)));
        ShadowScanCoordinator coordinator = new ShadowScanCoordinator(
            "deposit", 64, adapter, repository, repository, POLICY, reporter);

        coordinator.cycle();
        coordinator.cycle();

        assertEquals(1, repository.cursorLoads);
        assertEquals("deposit-shadow", repository.loadedScannerId);
        assertEquals(2, reporter.batches.size());
        assertEquals(List.of(0, 1), reporter.batches);
        assertEquals(1, reporter.diffs.size());
        assertEquals("canonical_unit", reporter.diffs.getFirst().kind());
    }

    private static ChainPoint point(long position) {
        return new ChainPoint("", Long.toString(position), "hash-" + position, List.of(), null);
    }

    private static final class ScriptedAdapter implements ChainScanAdapter {
        private final List<ChainPoint> points;

        private ScriptedAdapter(List<ChainPoint> points) {
            this.points = points;
        }

        @Override
        public ChainRef chain() {
            return CHAIN;
        }

        @Override
        public ChainPoint observationHead(FinalityPolicy policy) {
            return point(2);
        }

        @Override
        public CursorCheck verifyCursor(ScanCursor cursor) {
            return new CursorCheck(true, "");
        }

        @Override
        public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
            int index = cursor.point() == null ? 0 : Integer.parseInt(cursor.point().position());
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
            throw new AssertionError("shadow mode must not roll back");
        }

        @Override
        public ChainPoint finalityHead(FinalityPolicy policy) {
            throw new AssertionError("shadow mode must not advance finality");
        }
    }

    private static final class ReadOnlyRepository implements ShadowStateReader, ShadowReferenceReader {
        private int cursorLoads;
        private String loadedScannerId;

        @Override
        public ScanCursor loadCursor(ChainRef chain, String scannerId) {
            cursorLoads++;
            loadedScannerId = scannerId;
            return new ScanCursor(chain, scannerId, null, 0);
        }

        @Override
        public WatchSnapshot loadWatchSnapshot(ChainRef chain) {
            return new WatchSnapshot(0, List.of());
        }

        @Override
        public Optional<ChainPoint> canonicalPoint(ChainRef chain, String position) {
            return position.equals("1") ? Optional.of(point(1)) : Optional.empty();
        }

        @Override
        public boolean matchesObservation(DepositObservation observation) {
            return true;
        }

    }

    private static final class RecordingReporter implements ShadowScanReporter {
        private final List<Integer> batches = new ArrayList<>();
        private final List<ShadowDiff> diffs = new ArrayList<>();

        @Override
        public void recordBatch(String scannerId, ScanBatch batch, int differences) {
            batches.add(differences);
        }

        @Override
        public void recordDiff(ShadowDiff diff) {
            diffs.add(diff);
        }
    }
}