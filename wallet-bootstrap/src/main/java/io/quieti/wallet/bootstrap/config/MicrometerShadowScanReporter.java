package io.quieti.wallet.bootstrap.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ShadowDiff;
import io.quieti.wallet.application.scan.ShadowScanReporter;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MicrometerShadowScanReporter implements ShadowScanReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MicrometerShadowScanReporter.class);

    private final MeterRegistry registry;

    MicrometerShadowScanReporter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void recordBatch(String scannerId, ScanBatch batch, int differences) {
        String chain = batch.nextCursor().chain().toString();
        registry.counter(
                "wallet.shadow.batches",
                "chain", chain,
                "scanner", scannerId,
                "result", differences == 0 ? "match" : "difference").increment();
        registry.counter(
                "wallet.shadow.items",
                "chain", chain,
                "scanner", scannerId,
                "kind", "canonical_unit").increment(batch.units().size());
        registry.counter(
                "wallet.shadow.items",
                "chain", chain,
                "scanner", scannerId,
                "kind", "observation").increment(batch.observations().size());
    }

    @Override
    public void recordDiff(ShadowDiff diff) {
        registry.counter(
                "wallet.shadow.differences",
                "chain", diff.chain().toString(),
                "scanner", diff.scannerId(),
                "kind", diff.kind()).increment();
        LOGGER.atWarn()
                .addKeyValue("chain", diff.chain())
                .addKeyValue("scanner", diff.scannerId())
                .addKeyValue("kind", diff.kind())
                .addKeyValue("position", diff.position())
                .addKeyValue("expected", diff.expected())
                .addKeyValue("actual", diff.actual())
                .log("Shadow scan semantic difference");
    }
}