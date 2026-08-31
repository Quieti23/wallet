package io.quieti.wallet.application.scan;

public interface ShadowScanReporter {

    void recordBatch(String scannerId, ScanBatch batch, int differences);

    void recordDiff(ShadowDiff diff);
}