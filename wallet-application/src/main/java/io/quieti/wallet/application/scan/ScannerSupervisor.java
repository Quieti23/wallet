package io.quieti.wallet.application.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScannerSupervisor implements AutoCloseable {

    private final CycleFailureReporter reporter;
    private final List<SupervisedScanner> entries = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private ScheduledExecutorService executor;
    private boolean running;
    private volatile boolean closed;

    public ScannerSupervisor(CycleFailureReporter reporter) {
        this.reporter = reporter;
    }

    public synchronized void add(SupervisedScanner entry) {
        Objects.requireNonNull(entry, "entry");
        if (running || closed) {
            throw new IllegalStateException("supervisor is already running or closed");
        }
        String key = scannerKey(entry);
        if (!keys.add(key)) {
            throw new IllegalArgumentException("duplicate scanner: " + key);
        }
        entries.add(entry);
    }

    public synchronized void start() {
        if (running || closed) {
            throw new IllegalStateException("supervisor is already running or closed");
        }
        running = true;
        if (entries.isEmpty()) {
            return;
        }
        executor = new ScheduledThreadPoolExecutor(entries.size(), scannerThreadFactory());
        for (SupervisedScanner entry : entries) {
            executor.scheduleAtFixedRate(
                    () -> runCycle(entry),
                    0,
                    entry.pollInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
        }
    }

    public synchronized boolean isRunning() {
        return running && !closed;
    }

    private void runCycle(SupervisedScanner entry) {
        try {
            entry.runner().cycle();
        } catch (CancellationException exception) {
            if (!closed && reporter != null) {
                reporter.report(entry.chain(), entry.scannerId(), exception);
            }
        } catch (RuntimeException exception) {
            if (reporter != null) {
                reporter.report(entry.chain(), entry.scannerId(), exception);
            }
        }
    }

    @Override
    public void close() {
        List<SupervisedScanner> values;
        ScheduledExecutorService currentExecutor;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            values = List.copyOf(entries);
            currentExecutor = executor;
        }
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            try {
                currentExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        RuntimeException failure = null;
        for (int index = values.size() - 1; index >= 0; index--) {
            try {
                values.get(index).runtime().close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("close scanner runtimes");
                }
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static ThreadFactory scannerThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> Thread.ofPlatform()
                .name("chain-scan-" + sequence.incrementAndGet())
                .daemon(false)
                .unstarted(task);
    }

    private static String scannerKey(SupervisedScanner entry) {
        return entry.chain() + "/" + entry.scannerId();
    }
}