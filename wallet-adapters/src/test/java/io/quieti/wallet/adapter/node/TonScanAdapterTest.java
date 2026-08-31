package io.quieti.wallet.adapter.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.ton.ToncenterApi;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TonScanAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ChainRef CHAIN = ChainRef.parse("ton:testnet");

    @Test
    void preservesAllParentsAndEmitsNativeAndAllowlistedJetton() {
        FakeToncenter api = new FakeToncenter();
        TonScanAdapter adapter = adapter(api);
        ChainPoint cursorPoint = new ChainPoint(
                "masterchain", "100", "root100", List.of(), null);

        ScanBatch batch = adapter.scanNext(
                new ScanCursor(CHAIN, "deposit", cursorPoint, 7),
                new WatchSnapshot(1, List.of(
                        new WatchTarget("EQNative", "owner-native", "account-native"),
                        new WatchTarget("EQJettonOwner", "owner-jetton", "account-jetton"))),
                new ChainPoint("masterchain", "101", "root101", List.of(), null));

        assertEquals("101", batch.nextCursor().point().position());
        assertEquals(8, batch.nextCursor().version());
        assertEquals(List.of("masterchain", "0:4000000000000000"), batch.nextCursor().point().parents().stream()
                .map(parent -> parent.scope()).toList());
        assertEquals(List.of("message:msg-native", "jetton-transfer:42"), batch.observations().stream()
                .map(observation -> observation.id().source()).toList());
        assertEquals(List.of("1200000000", "2500000"), batch.observations().stream()
                .map(observation -> observation.amount().raw()).toList());
        assertEquals(List.of("native", "jetton"), batch.observations().stream()
                .map(observation -> observation.asset().standard()).toList());
        assertEquals("EQSourceWallet", batch.observations().get(1).metadata().get("jetton_source_wallet"));
        assertEquals("owner-jetton", batch.observations().get(1).metadata().get("owner_id"));
    }

    @Test
    void resolvesObservationAndFinalityMasterchainDepths() {
        FakeToncenter api = new FakeToncenter();
        TonScanAdapter adapter = adapter(api);
        FinalityPolicy policy = new FinalityPolicy() {
            @Override
            public FinalityTarget observationTarget(ChainRef chain) {
                return new FinalityTarget("masterchain-depth", "0");
            }

            @Override
            public FinalityTarget finalityTarget(ChainRef chain) {
                return new FinalityTarget("masterchain-depth", "1");
            }
        };

        assertEquals("101", adapter.observationHead(policy).position());
        assertEquals("100", adapter.finalityHead(policy).position());
    }

    private static TonScanAdapter adapter(ToncenterApi api) {
        return new TonScanAdapter(
                "testnet",
                101,
                api,
                (chain, position) -> Optional.empty(),
                List.of(new TonScanAdapter.JettonConfig("EQJettonMaster", 6)));
    }

    private static final class FakeToncenter implements ToncenterApi {

        private final Map<String, JsonNode> blocks = Map.of(
                "-1:-9223372036854775808:101", json("""
                        {"workchain":-1,"shard":"-9223372036854775808","seqno":101,
                         "root_hash":"root101","gen_utime":"1700000000","prev_blocks":[
                           {"workchain":-1,"shard":"-9223372036854775808","seqno":100},
                           {"workchain":0,"shard":"4000000000000000","seqno":55}]}
                        """),
                "-1:-9223372036854775808:100", json("""
                        {"workchain":-1,"shard":"-9223372036854775808","seqno":100,
                         "root_hash":"root100","gen_utime":"1699999999","prev_blocks":[]}
                        """),
                "0:4000000000000000:55", json("""
                        {"workchain":0,"shard":"4000000000000000","seqno":55,
                         "root_hash":"shard55","gen_utime":"1699999999","prev_blocks":[]}
                        """));

        @Override
        public JsonNode masterchainInfo() {
            return json("{\"last\":{\"seqno\":101,\"root_hash\":\"root101\"}}");
        }

        @Override
        public JsonNode block(long workchain, String shard, long seqno) {
            JsonNode block = blocks.get(workchain + ":" + shard + ":" + seqno);
            if (block == null) {
                throw new AssertionError("Unexpected block request");
            }
            return block;
        }

        @Override
        public List<JsonNode> transactions(long masterchainSeqno) {
            return List.of(
                    json("""
                            {"hash":"tx-native","description":{"aborted":false},"in_msg":{
                             "hash":"msg-native","source":"EQSender","destination":"EQNative",
                             "value":"1200000000","bounced":false}}
                            """),
                    json("""
                            {"hash":"tx-jetton","description":{"aborted":false},"in_msg":{
                             "hash":"msg-jetton","source":"EQSender","destination":"EQJettonOwner",
                             "value":"10000000","bounced":false}}
                            """));
        }

        @Override
        public List<JsonNode> jettonTransfers(long blockTime) {
            return List.of(json("""
                    {"transaction_hash":"tx-jetton","transaction_aborted":false,"query_id":"42",
                     "jetton_master":"EQJettonMaster","source_wallet":"EQSourceWallet",
                     "source":"EQSender","destination":"EQJettonOwner","amount":"2500000"}
                    """));
        }
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
    }
}