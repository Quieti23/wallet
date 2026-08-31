package io.quieti.wallet.application.scan;

import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import io.quieti.wallet.domain.deposit.DepositObservation;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class ShadowScanCoordinator implements ScanCycleRunner {

    private final String scannerId;
    private final long maxReorgDepth;
    private final ChainScanAdapter adapter;
    private final ShadowStateReader stateReader;
    private final ShadowReferenceReader referenceReader;
    private final FinalityPolicy policy;
    private final ShadowScanReporter reporter;
    private ScanCursor cursor;

    public ShadowScanCoordinator(
            String scannerId,
            long maxReorgDepth,
            ChainScanAdapter adapter,
            ShadowStateReader stateReader,
            ShadowReferenceReader referenceReader,
            FinalityPolicy policy,
            ShadowScanReporter reporter) {
        this.scannerId = requireText(scannerId, "scannerId");
        if (maxReorgDepth <= 0) {
            throw new IllegalArgumentException("maxReorgDepth must be positive");
        }
        this.maxReorgDepth = maxReorgDepth;
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.stateReader = Objects.requireNonNull(stateReader, "stateReader");
        this.referenceReader = Objects.requireNonNull(referenceReader, "referenceReader");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    @Override
    public void cycle() {
        ChainRef chain = adapter.chain();
        if (cursor == null) {
            cursor = stateReader.loadCursor(chain, scannerId + "-shadow");
        }
        WatchSnapshot watches = stateReader.loadWatchSnapshot(chain);
        CursorCheck check = Objects.requireNonNull(adapter.verifyCursor(cursor), "cursorCheck");
        if (!check.canonical()) {
            reporter.recordDiff(new ShadowDiff(
                    chain,
                    scannerId,
                    "cursor",
                    cursor.point() == null ? "" : cursor.point().position(),
                    "canonical",
                    check.reason()));
                    ChainPoint ancestor = Objects.requireNonNull(
                            adapter.findCommonAncestor(cursor, maxReorgDepth), "commonAncestor");
                    cursor = new ScanCursor(
                        chain, cursor.scannerId(), ancestor, cursor.version() + 1);
            return;
        }
        ChainPoint head = Objects.requireNonNull(adapter.observationHead(policy), "observationHead");
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("shadow scan cycle interrupted");
            }
            ScanBatch batch = Objects.requireNonNull(
                    adapter.scanNext(cursor, watches, head), "scanBatch");
            batch.validateAgainst(cursor);
            if (!batch.progress()) {
                return;
            }
            int differences = compare(batch);
            reporter.recordBatch(scannerId, batch, differences);
            cursor = batch.nextCursor();
        }
    }

    private int compare(ScanBatch batch) {
        int differences = 0;
        for (CanonicalUnit unit : batch.units()) {
            ChainPoint actual = referenceReader
                    .canonicalPoint(unit.chain(), unit.point().position())
                    .orElse(null);
            if (!unit.point().equals(actual)) {
                differences++;
                reporter.recordDiff(new ShadowDiff(
                        unit.chain(),
                        scannerId,
                        "canonical_unit",
                        unit.point().position(),
                        unit.point().hash(),
                        actual == null ? "missing" : actual.hash()));
            }
        }
        for (DepositObservation observation : batch.observations()) {
            if (!referenceReader.matchesObservation(observation)) {
                differences++;
                reporter.recordDiff(new ShadowDiff(
                        observation.id().chain(),
                        scannerId,
                        "observation",
                        observation.inclusion().position(),
                        observation.id().toString(),
                        "missing_or_different"));
            }
        }
        return differences;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}