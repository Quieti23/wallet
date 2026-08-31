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
            "wallet.scanners.solana.enabled=true",
            "wallet.scanners.solana.scanner-id=sol-deposit",
            "wallet.scanners.solana.poll-interval=1h",
            "wallet.scanners.solana.provider-keys[0]=SOL_PRIMARY",
            "wallet.scanners.solana.provider-keys[1]=SOL_SECONDARY",
            "wallet.rpc.chains.SOL_PRIMARY.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.SOL_PRIMARY.start-height=40",
            "wallet.rpc.chains.SOL_PRIMARY.start-hash=Blockhash40",
            "wallet.rpc.chains.SOL_SECONDARY.endpoint=http://127.0.0.1:2",
            "wallet.rpc.chains.SOL_SECONDARY.start-height=40",
            "wallet.rpc.chains.SOL_SECONDARY.start-hash=Blockhash40"
        })
class SolanaScannerConfigurationTest {

    @Autowired
    private List<SupervisedScanner> scanners;

    @MockitoBean
    private ScannerSupervisorLifecycle lifecycle;

    @Test
    void configuresDevnetScannerWithIndependentProviders() {
        assertThat(scanners).singleElement().satisfies(scanner -> {
            assertThat(scanner.chain().toString())
                    .isEqualTo("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1");
            assertThat(scanner.scannerId()).isEqualTo("sol-deposit");
            assertThat(scanner.pollInterval()).isEqualTo(Duration.ofHours(1));
        });
    }
}