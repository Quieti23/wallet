package io.quieti.wallet.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

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
            "wallet.scanners.ton.enabled=true",
            "wallet.scanners.ton.network=testnet",
            "wallet.scanners.ton.scanner-id=ton-deposit",
            "wallet.scanners.ton.poll-interval=1h",
            "wallet.scanners.ton.provider-key=TONCENTER",
            "wallet.scanners.ton.jettons[0].master=EQJettonMaster",
            "wallet.scanners.ton.jettons[0].decimals=6",
            "wallet.rpc.chains.TONCENTER.endpoint=http://127.0.0.1:1/api/v3",
            "wallet.rpc.chains.TONCENTER.start-height=100",
            "wallet.rpc.chains.TONCENTER.start-hash=root100"
        })
class TonScannerConfigurationTest {

    @Autowired
    private List<SupervisedScanner> scanners;

    @MockitoBean
    private ScannerSupervisorLifecycle lifecycle;

    @Test
    void configuresTestnetToncenterScanner() {
        assertThat(scanners).singleElement().satisfies(scanner -> {
            assertThat(scanner.chain().toString()).isEqualTo("ton:testnet");
            assertThat(scanner.scannerId()).isEqualTo("ton-deposit");
            assertThat(scanner.pollInterval()).isEqualTo(Duration.ofHours(1));
        });
    }
}