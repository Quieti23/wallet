package io.quieti.wallet.application.scan;

import io.quieti.wallet.application.port.ScannerRepository;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class ScanCoordinator implements ScanCycleRunner {

    private final ScanCoordinatorConfig config;
    private final ChainScanAdapter adapter;
    private final ScannerRepository repository;
    private final FinalityPolicy policy;

    public ScanCoordinator(
            ScanCoordinatorConfig config,
            ChainScanAdapter adapter,
            ScannerRepository repository,
            FinalityPolicy policy) {
        this.config = Objects.requireNonNull(config, "config");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(policy.observationTarget(adapter.chain()), "observationTarget");
        Objects.requireNonNull(policy.finalityTarget(adapter.chain()), "finalityTarget");
    }

    public ChainRef chain() {
        return adapter.chain();
    }

    public String scannerId() {
        return config.scannerId();
    }

    @Override
    public void cycle() {
        StaleCursorException stale = null;
        for (int attempt = 0; attempt <= config.maxCycleRetries(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("scan cycle interrupted");
            }
            try {
                cycleOnce();
                return;
            } catch (StaleCursorException exception) {
                stale = exception;
            }
        }
        throw new IllegalStateException(
                "scan cycle exceeded " + config.maxCycleRetries() + " stale-cursor retries",
                stale);
    }

    private void cycleOnce() {
        ChainRef chain = adapter.chain();
        ScanCursor cursor = repository.loadCursor(chain, config.scannerId());
        if (!cursor.chain().equals(chain) || !cursor.scannerId().equals(config.scannerId())) {
            throw new IllegalArgumentException("loaded cursor identity does not match coordinator");
        }
        WatchSnapshot watches = repository.loadWatchSnapshot(chain);
        ensureCanonical(cursor);

        ChainPoint observationHead = Objects.requireNonNull(
                adapter.observationHead(policy), "observationHead");
        ChainPoint finalityHead = Objects.requireNonNull(
                adapter.finalityHead(policy), "finalityHead");
        ScanCursor advanced = scanAvailable(cursor, watches, observationHead);
        repository.advanceFinality(new FinalityRequest(
                chain,
                config.scannerId(),
                advanced.version(),
                new FinalityEvaluation(observationHead, finalityHead),
                List.of()));
    }

    private void ensureCanonical(ScanCursor cursor) {
        CursorCheck check = Objects.requireNonNull(adapter.verifyCursor(cursor), "cursorCheck");
        if (check.canonical()) {
            return;
        }
        ChainPoint ancestor = Objects.requireNonNull(
                adapter.findCommonAncestor(cursor, config.maxReorgDepth()), "commonAncestor");
        repository.rollback(new RollbackRequest(
                cursor.chain(),
                cursor.scannerId(),
                cursor.version(),
                ancestor,
                List.of()));
        throw new StaleCursorException("successful rollback reload");
    }

    private ScanCursor scanAvailable(
            ScanCursor initial,
            WatchSnapshot watches,
            ChainPoint observationHead) {
        ScanCursor cursor = initial;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("scan cycle interrupted");
            }
            ScanBatch batch = Objects.requireNonNull(
                    adapter.scanNext(cursor, watches, observationHead), "scanBatch");
            batch.validateAgainst(cursor);
            if (!batch.progress()) {
                return cursor;
            }
            repository.commit(new CommitRequest(
                    cursor.chain(), cursor.scannerId(), cursor.version(), batch, List.of()));
            cursor = batch.nextCursor();
        }
    }
}