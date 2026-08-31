package io.quieti.wallet.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.ShadowDiff;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class MicrometerShadowScanReporterTest {

    @Test
    void recordsLowCardinalityBatchAndDifferenceMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerShadowScanReporter reporter = new MicrometerShadowScanReporter(registry);
        ChainRef chain = ChainRef.parse("bip122:signet");
        ChainPoint point = new ChainPoint("", "1", "hash-1", List.of(), null);
        ScanBatch batch = new ScanBatch(
                List.of(),
                List.of(),
                new ScanCursor(chain, "deposit-shadow", point, 1),
                true);

        reporter.recordBatch("deposit", batch, 1);
        reporter.recordDiff(new ShadowDiff(
                chain, "deposit", "canonical_unit", "1", "hash-1", "missing"));

        assertEquals(1, registry.get("wallet.shadow.batches")
                .tag("chain", chain.toString())
                .tag("scanner", "deposit")
                .tag("result", "difference")
                .counter()
                .count());
        assertEquals(1, registry.get("wallet.shadow.differences")
                .tag("kind", "canonical_unit")
                .counter()
                .count());
    }
}