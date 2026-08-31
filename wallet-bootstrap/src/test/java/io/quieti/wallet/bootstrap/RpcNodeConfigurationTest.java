package io.quieti.wallet.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.quieti.wallet.application.port.NodePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "wallet.rpc.enabled=true",
            "wallet.rpc.chains.BTC_TESTNET.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.BTC_TESTNET.start-height=100",
            "wallet.rpc.chains.BTC_TESTNET.start-hash=btc-100",
            "wallet.rpc.chains.EVM.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.EVM.start-height=200",
            "wallet.rpc.chains.EVM.start-hash=evm-200",
            "wallet.rpc.chains.SOL.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.SOL.start-height=300",
            "wallet.rpc.chains.SOL.start-hash=sol-300",
            "wallet.rpc.chains.SOL.max-skipped-positions=8",
            "wallet.rpc.chains.TON.endpoint=http://127.0.0.1:1",
            "wallet.rpc.chains.TON.start-height=400",
            "wallet.rpc.chains.TON.start-hash=ton-400"
        })
class RpcNodeConfigurationTest {

    @Autowired
    private List<NodePort> nodePorts;

    @Test
    void configuresOneRouterWithTrustedCheckpoints() {
        assertThat(nodePorts).hasSize(1);
        NodePort nodePort = nodePorts.getFirst();
        assertThat(nodePort.initialCheckpoint("BTC_TESTNET").blockHash()).isEqualTo("btc-100");
        assertThat(nodePort.initialCheckpoint("EVM").height()).isEqualTo(200);
        assertThat(nodePort.initialCheckpoint("SOL").height()).isEqualTo(300);
        assertThat(nodePort.initialCheckpoint("TON").blockHash()).isEqualTo("ton-400");
    }
}