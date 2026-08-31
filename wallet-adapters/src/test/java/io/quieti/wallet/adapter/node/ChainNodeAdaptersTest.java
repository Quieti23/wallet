package io.quieti.wallet.adapter.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class ChainNodeAdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ChainCheckpoint CHECKPOINT = new ChainCheckpoint(99, "parent-99");
    private static final String EVM_BLOCK_HASH = "0x" + "1".repeat(64);
    private static final String EVM_PARENT_HASH = "0x" + "2".repeat(64);

    @Test
    void mapsBitcoinTestnetBlockByHeightAndHash() {
        RecordingTransport rpc = responses(
                "\"block-100\"",
                """
                        {"height":100,"hash":"block-100","previousblockhash":"parent-99"}
                        """);
        BitcoinNodeAdapter adapter = new BitcoinNodeAdapter("BTC_TESTNET", CHECKPOINT, rpc);

        ChainBlock block = adapter.fetchBlock("BTC_TESTNET", 100);

        assertEquals(new ChainBlock("BTC_TESTNET", 100, "block-100", "parent-99"), block);
        assertEquals(List.of("getblockhash", "getblock"), rpc.methods);
        assertThrows(IllegalArgumentException.class,
                () -> new BitcoinNodeAdapter("BTC_MAINNET", CHECKPOINT, rpc));
    }

    @Test
    void mapsEvmBlockAndValidatesHexHeight() {
        EvmNodeAdapter adapter = new EvmNodeAdapter(CHECKPOINT, responses("""
            {"number":"0x64","hash":"%s","parentHash":"%s","timestamp":"0x1"}
            """.formatted(EVM_BLOCK_HASH, EVM_PARENT_HASH)));

        assertEquals(new ChainBlock("EVM", 100, EVM_BLOCK_HASH, EVM_PARENT_HASH),
                adapter.fetchBlock("EVM", 100));
    }

    @Test
    void skipsOnlyABoundedNumberOfEmptySolanaSlots() {
        RecordingTransport rpc = responses(
                "null",
                """
                        {"blockhash":"block-101","previousBlockhash":"parent-99","parentSlot":99}
                        """);
        SolanaNodeAdapter adapter = new SolanaNodeAdapter(CHECKPOINT, rpc, 2);

        ChainBlock block = adapter.fetchBlock("SOL", 100);

        assertEquals(new ChainBlock("SOL", 101, "block-101", "parent-99"), block);
        assertEquals(List.of("getBlock", "getBlock"), rpc.methods);
    }

    @Test
    void mapsTonMasterchainBlockAndHeaderParent() {
        RecordingTransport rpc = responses(
                """
                        {"workchain":-1,"shard":"-9223372036854775808","seqno":100,
                         "root_hash":"block-100","file_hash":"file-100"}
                        """,
                """
                        {"prev_blocks":[{"root_hash":"parent-99"}]}
                        """);
        TonNodeAdapter adapter = new TonNodeAdapter(CHECKPOINT, rpc);

        ChainBlock block = adapter.fetchBlock("TON", 100);

        assertEquals(new ChainBlock("TON", 100, "block-100", "parent-99"), block);
        assertEquals(List.of("lookupBlock", "getBlockHeader"), rpc.methods);
    }

    @Test
    void routesOnlyConfiguredChains() {
        EvmNodeAdapter evm = new EvmNodeAdapter(CHECKPOINT, responses("""
                {"number":"0x64","hash":"block-100","parentHash":"parent-99"}
                """));
        RoutingNodeAdapter routing = new RoutingNodeAdapter(Map.of("EVM", evm));

        assertEquals(CHECKPOINT, routing.initialCheckpoint("EVM"));
        assertThrows(IllegalArgumentException.class, () -> routing.initialCheckpoint("UNKNOWN"));
    }

    private static RecordingTransport responses(String... responses) {
        Queue<JsonNode> nodes = new ArrayDeque<>();
        for (String response : responses) {
            try {
                nodes.add(MAPPER.readTree(response));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException(error);
            }
        }
        return new RecordingTransport(nodes);
    }

    private static final class RecordingTransport implements RpcTransport {
        private final Queue<JsonNode> responses;
        private final List<String> methods = new java.util.ArrayList<>();

        private RecordingTransport(Queue<JsonNode> responses) {
            this.responses = responses;
        }

        @Override
        public JsonNode call(String method, Object... parameters) {
            methods.add(method);
            JsonNode response = responses.poll();
            if (response == null) {
                throw new AssertionError("Unexpected RPC call: " + method);
            }
            return response;
        }
    }
}