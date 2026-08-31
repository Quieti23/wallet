package io.quieti.wallet.adapter.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.port.CanonicalReader;
import io.quieti.wallet.application.scan.CursorCheck;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class BitcoinScanAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ChainRef SIGNET = ChainRef.parse("bip122:signet");
    private static final String PREVIOUS = hash(1);
    private static final String CURRENT = hash(2);
    private static final String TRANSACTION = hash(3);

    @Test
    void scansInitialHeightAndCreatesScriptObservation() {
        RecordingTransport rpc = responses(
                jsonString(CURRENT),
                """
                {"hash":"%s","height":10,"previousblockhash":"%s","time":123,
                 "tx":[{"txid":"%s","vout":[
                   {"value":0.00042000,"scriptPubKey":{"hex":"0014ABCD"}},
                   {"value":1.0,"scriptPubKey":{"hex":"51"}}
                 ]}]}
                """.formatted(CURRENT, PREVIOUS, TRANSACTION));
        BitcoinNodeAdapter adapter = adapter(rpc, position -> Optional.empty(), 3);
        ScanCursor cursor = new ScanCursor(SIGNET, "deposit", null, 7);

        ScanBatch batch = adapter.scanNext(
                cursor,
                new WatchSnapshot(1, List.of(
                        new WatchTarget("0014abcd", "owner-1", "account-1"))),
                point(10, CURRENT));

        assertTrue(batch.progress());
        assertEquals(8, batch.nextCursor().version());
        assertEquals(1, batch.units().size());
        assertEquals(1, batch.observations().size());
        assertEquals("vout:0", batch.observations().getFirst().id().source());
        assertEquals("42000", batch.observations().getFirst().amount().raw());
        assertEquals("3", batch.observations().getFirst().metadata().get("required_confirmations"));
        assertEquals(List.of("getblockhash", "getblock"), rpc.methods);
    }

    @Test
    void resolvesConfirmationHeadAndRejectsUnsynchronizedNode() {
        RecordingTransport rpc = responses(
            "{\"chain\":\"signet\",\"blocks\":10,\"headers\":10,\"initialblockdownload\":false}",
                jsonString(hash(9)),
            "{\"hash\":\"%s\",\"height\":8,\"previousblockhash\":\"%s\",\"time\":123}"
                        .formatted(hash(9), hash(8)));
        BitcoinNodeAdapter adapter = adapter(rpc, position -> Optional.empty(), 3);

        ChainPoint head = adapter.finalityHead(policy(
                new FinalityTarget("tag", "latest"),
                new FinalityTarget("confirmations", "3")));

        assertEquals("8", head.position());

        BitcoinNodeAdapter unsynchronized = adapter(responses(
            "{\"chain\":\"signet\",\"blocks\":10,\"headers\":11,\"initialblockdownload\":true}"),
                position -> Optional.empty(),
                1);
        assertThrows(IllegalStateException.class,
                () -> unsynchronized.observationHead(policy(
                        new FinalityTarget("tag", "latest"),
                        new FinalityTarget("confirmations", "1"))));
    }

    @Test
    void detectsReorgAndFindsCommonAncestor() {
        ChainPoint genesis = point(0, hash(10));
        ChainPoint oldTip = point(1, hash(11));
        Map<String, ChainPoint> history = new HashMap<>();
        history.put("0", genesis);
        history.put("1", oldTip);
        RecordingTransport rpc = responses(
            "{\"chain\":\"signet\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false}",
                jsonString(hash(12)),
            "{\"chain\":\"signet\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false}",
                jsonString(hash(12)),
                jsonString(hash(10)));
        BitcoinNodeAdapter adapter = adapter(rpc, position -> Optional.ofNullable(history.get(position)), 1);
        ScanCursor cursor = new ScanCursor(SIGNET, "deposit", oldTip, 1);

        CursorCheck check = adapter.verifyCursor(cursor);
        assertFalse(check.canonical());
        assertEquals(genesis, adapter.findCommonAncestor(cursor, 5));
    }

    @Test
    void rejectsMainnetAndChainDiscontinuity() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitcoinNodeAdapter(
                        "BTC_MAINNET", new ChainCheckpoint(9, PREVIOUS), responses(),
                        (chain, position) -> Optional.empty(), 1));

        BitcoinNodeAdapter adapter = adapter(responses(
                jsonString(CURRENT),
                """
                {"hash":"%s","height":10,"previousblockhash":"%s","time":123,"tx":[]}
                """.formatted(CURRENT, hash(99))), position -> Optional.empty(), 1);
        assertThrows(IllegalStateException.class,
                () -> adapter.scanNext(
                        new ScanCursor(SIGNET, "deposit", null, 0),
                        new WatchSnapshot(0, List.of()),
                        point(10, CURRENT)));
    }

    private static BitcoinNodeAdapter adapter(
            RecordingTransport rpc,
            PositionHistory history,
            long confirmations) {
        CanonicalReader reader = (chain, position) -> history.find(position);
        return new BitcoinNodeAdapter(
                "BTC_SIGNET",
                new ChainCheckpoint(9, PREVIOUS),
                rpc,
                reader,
                confirmations);
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

    private static ChainPoint point(long height, String hash) {
        return new ChainPoint("", Long.toString(height), hash, List.of(), null);
    }

    private static String hash(int value) {
        return "%064x".formatted(value);
    }

    private static String jsonString(String value) {
        return "\"" + value + "\"";
    }

    private static RecordingTransport responses(String... values) {
        Queue<JsonNode> responses = new ArrayDeque<>();
        for (String value : values) {
            try {
                responses.add(MAPPER.readTree(value));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        return new RecordingTransport(responses);
    }

    @FunctionalInterface
    private interface PositionHistory {
        Optional<ChainPoint> find(String position);
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
            JsonNode value = responses.poll();
            if (value == null) {
                throw new AssertionError("Unexpected RPC call: " + method);
            }
            return value;
        }
    }
}