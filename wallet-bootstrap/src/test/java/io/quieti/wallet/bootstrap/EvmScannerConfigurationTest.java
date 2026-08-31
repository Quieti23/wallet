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
            "wallet.scanners.evm.enabled=true",
            "wallet.scanners.evm.chain-id=11155111",
            "wallet.scanners.evm.scanner-id=evm-deposit",
            "wallet.scanners.evm.poll-interval=1h",
            "wallet.scanners.evm.max-reorg-depth=128",
            "wallet.scanners.evm.max-cycle-retries=2",
            "wallet.scanners.evm.observation-head=safe",
            "wallet.scanners.evm.finality-head=finalized",
            "wallet.scanners.evm.fallback-confirmations=12",
            "wallet.scanners.evm.include-internal=true",
            "wallet.scanners.evm.provider-keys[0]=EVM_PRIMARY",
            "wallet.scanners.evm.provider-keys[1]=EVM_SECONDARY",
            "wallet.rpc.chains.EVM_PRIMARY.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.EVM_PRIMARY.start-height=100",
            "wallet.rpc.chains.EVM_PRIMARY.start-hash=0x1111111111111111111111111111111111111111111111111111111111111111",
            "wallet.rpc.chains.EVM_SECONDARY.endpoint=http://127.0.0.1:2",
            "wallet.rpc.chains.EVM_SECONDARY.start-height=100",
            "wallet.rpc.chains.EVM_SECONDARY.start-hash=0x1111111111111111111111111111111111111111111111111111111111111111"
        })
class EvmScannerConfigurationTest {

    @Autowired
    private List<SupervisedScanner> scanners;

    @MockitoBean
    private ScannerSupervisorLifecycle lifecycle;

    @Test
    void configuresSepoliaScannerWithIndependentProviders() {
        assertThat(scanners).singleElement().satisfies(scanner -> {
            assertThat(scanner.chain().toString()).isEqualTo("eip155:11155111");
            assertThat(scanner.scannerId()).isEqualTo("evm-deposit");
            assertThat(scanner.pollInterval()).isEqualTo(Duration.ofHours(1));
        });
    }
}