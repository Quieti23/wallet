package io.quieti.wallet.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.quieti.wallet.application.scan.ShadowScanCoordinator;
import io.quieti.wallet.application.scan.SupervisedScanner;
import io.quieti.wallet.bootstrap.config.ScannerSupervisorLifecycle;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "wallet.scanners.shadow-mode=true",
            "wallet.scanners.btc.enabled=true",
            "wallet.scanners.btc.network=BTC_SIGNET",
            "wallet.scanners.btc.scanner-id=btc-deposit",
            "wallet.scanners.btc.poll-interval=1h",
            "wallet.scanners.btc.max-reorg-depth=144",
            "wallet.scanners.btc.max-cycle-retries=2",
            "wallet.scanners.btc.confirmations=6",
            "wallet.rpc.chains.BTC_SIGNET.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.BTC_SIGNET.start-height=100",
            "wallet.rpc.chains.BTC_SIGNET.start-hash=0000000000000000000000000000000000000000000000000000000000000064"
        })
class BitcoinScannerConfigurationTest {

    @Autowired
    private List<SupervisedScanner> scanners;

    @MockitoBean
    private ScannerSupervisorLifecycle lifecycle;

    @Test
    void configuresSignetScannerWithoutEnablingLegacyRpcRouter() {
        assertThat(scanners).singleElement().satisfies(scanner -> {
            assertThat(scanner.chain().toString()).isEqualTo("bip122:signet");
            assertThat(scanner.scannerId()).isEqualTo("btc-deposit");
            assertThat(scanner.pollInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(scanner.runner()).isInstanceOf(ShadowScanCoordinator.class);
        });
    }
}