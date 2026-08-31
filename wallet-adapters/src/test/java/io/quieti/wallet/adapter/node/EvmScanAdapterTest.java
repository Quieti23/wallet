package io.quieti.wallet.adapter.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.FailoverRpcTransport;
import io.quieti.wallet.adapter.rpc.RpcException;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvmScanAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ChainRef CHAIN = ChainRef.parse("eip155:11155111");
    private static final String PARENT = hex('1', 64);
    private static final String BLOCK = hex('2', 64);
    private static final String TRANSACTION = hex('3', 64);
    private static final String WATCHED = hex('4', 40);
    private static final String SENDER = hex('5', 40);
    private static final String TOKEN = hex('6', 40);

    @Test
    void emitsSuccessfulNativeAndErc20Observations() {
        RecordingTransport rpc = responses(
                """
                {"number":"0xa","hash":"%s","parentHash":"%s","timestamp":"0x64","transactions":[
                  {"hash":"%s","from":"%s","to":"%s","value":"0x7"}
                ]}
                """.formatted(BLOCK, PARENT, TRANSACTION, SENDER, WATCHED),
                """
                {"status":"0x1","blockHash":"%s"}
                """.formatted(BLOCK),
                """
                [{"address":"%s","topics":["%s","%s","%s"],"data":"0x9",
                  "transactionHash":"%s","logIndex":"0x2","removed":false}]
                """.formatted(TOKEN, transferTopic(), addressTopic(SENDER), addressTopic(WATCHED), TRANSACTION));
        EvmNodeAdapter adapter = adapter(rpc);

        ScanBatch batch = adapter.scanNext(
                new ScanCursor(CHAIN, "deposit", null, 4),
                new WatchSnapshot(0, List.of(new WatchTarget(WATCHED, "owner-1", "account-1"))),
                point(10, BLOCK));

        assertEquals(2, batch.observations().size());
        assertEquals(List.of("tx", "log:2"), batch.observations().stream()
                .map(observation -> observation.id().source()).toList());
        assertEquals("7", batch.observations().get(0).amount().raw());
        assertEquals("9", batch.observations().get(1).amount().raw());
        assertEquals("erc20", batch.observations().get(1).asset().standard());
        assertEquals(TOKEN, batch.observations().get(1).asset().locator());
        assertEquals("owner-1", batch.observations().get(1).metadata().get("owner_id"));
        assertEquals(5, batch.nextCursor().version());
        assertEquals(List.of("eth_getBlockByNumber", "eth_getTransactionReceipt", "eth_getLogs"), rpc.methods);
    }

    @Test
    void suppressesNativeObservationForFailedReceipt() {
        RecordingTransport rpc = responses(
                """
                {"number":"0xa","hash":"%s","parentHash":"%s","timestamp":"0x64","transactions":[
                  {"hash":"%s","from":"%s","to":"%s","value":"0x7"}
                ]}
                """.formatted(BLOCK, PARENT, TRANSACTION, SENDER, WATCHED),
                """
                {"status":"0x0","blockHash":"%s"}
                """.formatted(BLOCK),
                "[]");

        ScanBatch batch = adapter(rpc).scanNext(
                new ScanCursor(CHAIN, "deposit", null, 0),
                new WatchSnapshot(0, List.of(new WatchTarget(WATCHED, "owner", "account"))),
                point(10, BLOCK));

        assertFalse(batch.observations().stream()
                .anyMatch(observation -> observation.id().source().equals("tx")));
    }

            @Test
            void emitsInternalTransferAndDegradesUnsupportedTrace() {
            String block = """
                {"number":"0xa","hash":"%s","parentHash":"%s","timestamp":"0x64","transactions":[
                  {"hash":"%s","from":"%s","to":"%s","value":"0x7"}
                ]}
                """.formatted(BLOCK, PARENT, TRANSACTION, SENDER, WATCHED);
            String receipt = "{\"status\":\"0x1\",\"blockHash\":\"%s\"}".formatted(BLOCK);
            RecordingTransport rpc = responses(
                block,
                receipt,
                """
                {"type":"CALL","from":"%s","to":"%s","value":"0x7","calls":[
                  {"type":"CALL","from":"%s","to":"%s","value":"0x5"}
                ]}
                """.formatted(SENDER, WATCHED, SENDER, WATCHED),
                "[]");

            ScanBatch batch = adapter(rpc, true).scanNext(
                new ScanCursor(CHAIN, "deposit", null, 0),
                new WatchSnapshot(0, List.of(new WatchTarget(WATCHED, "owner", "account"))),
                point(10, BLOCK));

            assertEquals(List.of("tx", "trace:0.0"), batch.observations().stream()
                .map(observation -> observation.id().source()).toList());

            RpcTransport unsupportedTrace = new RpcTransport() {
                private final RecordingTransport delegate = responses(block, receipt, "[]");

                @Override
                public JsonNode call(String method, Object... parameters) {
                if (method.equals("debug_traceTransaction")) {
                    throw new RpcException("RPC error -32601: method not found");
                }
                return delegate.call(method, parameters);
                }
            };
            ScanBatch degraded = adapter(unsupportedTrace, true).scanNext(
                new ScanCursor(CHAIN, "deposit", null, 0),
                new WatchSnapshot(0, List.of(new WatchTarget(WATCHED, "owner", "account"))),
                point(10, BLOCK));
            assertEquals(List.of("tx"), degraded.observations().stream()
                .map(observation -> observation.id().source()).toList());
            }

            @Test
            void fallsBackFromSafeHeadAndFindsCommonAncestor() {
            String hash15 = hex('7', 64);
            String hash20 = hex('8', 64);
            RecordingTransport heads = responses(
                "null",
                header(20, hash20, hex('9', 64)),
                header(15, hash15, hex('a', 64)));
            ChainPoint fallback = adapter(heads).observationHead(policy(
                new FinalityTarget("tag", "safe"),
                new FinalityTarget("tag", "finalized")));
            assertEquals("15", fallback.position());

            String oldTip = hex('b', 64);
            RecordingTransport reorg = responses(
                header(2, hex('c', 64), hash15),
                header(2, hex('c', 64), hash15),
                header(1, hash15, PARENT));
            ChainPoint ancestor = new ChainPoint("", "1", hash15, List.of(), null);
            EvmNodeAdapter adapter = new EvmNodeAdapter(
                11155111,
                new ChainCheckpoint(0, PARENT),
                reorg,
                (chain, position) -> position.equals("1") ? Optional.of(ancestor) : Optional.empty(),
                5,
                false,
                null);
            assertEquals(ancestor, adapter.findCommonAncestor(
                new ScanCursor(CHAIN, "deposit", point(2, oldTip), 1), 2));
            }

            @Test
            void retriesReadOnNextRpcProvider() {
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger secondCalls = new AtomicInteger();
            RpcTransport failover = new FailoverRpcTransport(List.of(
                (method, parameters) -> {
                    firstCalls.incrementAndGet();
                    throw new RpcException("primary unavailable");
                },
                (method, parameters) -> {
                    secondCalls.incrementAndGet();
                    return MAPPER.getNodeFactory().textNode("ok");
                }));

            assertEquals("ok", failover.call("eth_chainId").asText());
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());
            }

    private static EvmNodeAdapter adapter(RecordingTransport rpc) {
        return adapter(rpc, false);
    }

    private static EvmNodeAdapter adapter(RpcTransport rpc, boolean includeInternal) {
        return new EvmNodeAdapter(
                11155111,
                new ChainCheckpoint(9, PARENT),
                rpc,
                (chain, position) -> Optional.empty(),
                5,
                includeInternal,
                null);
    }

    private static FinalityPolicy policy(FinalityTarget observation, FinalityTarget finality) {
        return new FinalityPolicy() {
            @Override
            public FinalityTarget observationTarget(ChainRef chain) {
                return observation;
            }

            @Override
            public FinalityTarget finalityTarget(ChainRef chain) {
                return finality;
            }
        };
    }

    private static String header(long height, String hash, String parent) {
        return """
                {"number":"0x%s","hash":"%s","parentHash":"%s","timestamp":"0x64"}
                """.formatted(Long.toHexString(height), hash, parent);
    }

    private static ChainPoint point(long height, String hash) {
        return new ChainPoint("", Long.toString(height), hash, List.of(), null);
    }

    private static String transferTopic() {
        return "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + address.substring(2);
    }

    private static String hex(char digit, int digits) {
        return "0x" + String.valueOf(digit).repeat(digits);
    }

    private static RecordingTransport responses(String... responses) {
        Queue<JsonNode> nodes = new ArrayDeque<>();
        for (String response : responses) {
            try {
                nodes.add(MAPPER.readTree(response));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        return new RecordingTransport(nodes);
    }

    private static final class RecordingTransport implements RpcTransport {
        private final Queue<JsonNode> responses;
        private final List<String> methods = new ArrayList<>();

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