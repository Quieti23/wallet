package io.quieti.wallet.adapter.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quieti.wallet.adapter.node.BitcoinNodeAdapter;
import io.quieti.wallet.adapter.node.EvmNodeAdapter;
import io.quieti.wallet.adapter.node.SolanaScanAdapter;
import io.quieti.wallet.adapter.node.TonScanAdapter;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.adapter.ton.ToncenterApi;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoBatchCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Map<String, String> GO_FIELDS = Map.ofEntries(
            Map.entry("units", "Units"),
            Map.entry("observations", "Observations"),
            Map.entry("nextCursor", "NextCursor"),
            Map.entry("progress", "Progress"),
            Map.entry("chain", "Chain"),
            Map.entry("point", "Point"),
            Map.entry("canonical", "Canonical"),
            Map.entry("namespace", "Namespace"),
            Map.entry("reference", "Reference"),
            Map.entry("scope", "Scope"),
            Map.entry("position", "Position"),
            Map.entry("hash", "Hash"),
            Map.entry("parents", "Parents"),
            Map.entry("time", "Time"),
            Map.entry("id", "ID"),
            Map.entry("asset", "Asset"),
            Map.entry("transaction", "Transaction"),
            Map.entry("source", "Source"),
            Map.entry("kind", "Kind"),
            Map.entry("value", "Value"),
            Map.entry("standard", "Standard"),
            Map.entry("locator", "Locator"),
            Map.entry("from", "From"),
            Map.entry("to", "To"),
            Map.entry("amount", "Amount"),
            Map.entry("raw", "Raw"),
            Map.entry("decimals", "Decimals"),
            Map.entry("inclusion", "Inclusion"),
            Map.entry("metadata", "Metadata"),
            Map.entry("scannerId", "ScannerID"),
            Map.entry("version", "Version"));

    @Test
    void bitcoinBatchMatchesGoFixture() throws IOException {
        String previous = hash(1);
        String current = hash(2);
        String transaction = hash(3);
        RpcTransport rpc = responses(
                jsonString(current),
                """
                {"hash":"%s","height":10,"previousblockhash":"%s","time":123,
                 "tx":[{"txid":"%s","vout":[
                   {"value":0.00042000,"scriptPubKey":{"hex":"0014ABCD"}}
                 ]}]}
                """.formatted(current, previous, transaction));
        BitcoinNodeAdapter adapter = new BitcoinNodeAdapter(
                "BTC_SIGNET",
                new ChainCheckpoint(9, previous),
                rpc,
                (chain, position) -> java.util.Optional.empty(),
                3);
        ChainRef chain = ChainRef.parse("bip122:signet");

        ScanBatch batch = adapter.scanNext(
                new ScanCursor(chain, "primary", null, 7),
                new WatchSnapshot(0, List.of(
                        new WatchTarget("0014abcd", "owner-1", "account-1"))),
                new ChainPoint("", "10", current, List.of(), null));

        assertGoFixture("btc-signet-native-vout.json", batch);
    }

        @Test
        void evmBatchMatchesGoFixture() throws IOException {
        String parent = "0x" + "0".repeat(63) + "1";
        String block = "0xf126ed3c86dc40ab4e7d85bc3df534229e7fc27be51bb461ca0d2f0598fbd5b6";
        String transaction = "0x4254f158a22adbb879846e509e43196ffaa7e9c336c74dc9852a335cca84b9ef";
        String sender = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf";
        String watched = "0x1000000000000000000000000000000000000001";
        String internalFrom = "0x3000000000000000000000000000000000000003";
        String token = "0x2000000000000000000000000000000000000002";
        RpcTransport rpc = responses(
            """
            {"number":"0xa","hash":"%s","parentHash":"%s","timestamp":"0x64","transactions":[
              {"hash":"%s","from":"%s","to":"%s","value":"0x7"}]}
            """.formatted(block, parent, transaction, sender, watched),
            "{\"status\":\"0x1\",\"blockHash\":\"%s\"}".formatted(block),
            """
            {"type":"CALL","from":"%s","to":"%s","value":"0x7","calls":[
              {"type":"CALL","from":"%s","to":"0x4000000000000000000000000000000000000004","value":"0x1"},
              {"type":"CALL","from":"%s","to":"%s","value":"0x5"}]}
            """.formatted(sender, watched, internalFrom, internalFrom, watched),
            """
            [{"address":"%s","topics":["%s","%s","%s"],"data":"0x9",
              "transactionHash":"%s","logIndex":"0x2","removed":false}]
            """.formatted(token, transferTopic(), addressTopic(internalFrom), addressTopic(watched), transaction));
        EvmNodeAdapter adapter = new EvmNodeAdapter(
            1,
            new ChainCheckpoint(9, parent),
            rpc,
            (chain, position) -> Optional.empty(),
            5,
            true,
            Set.of(token));
        ChainRef chain = ChainRef.parse("eip155:1");

        ScanBatch batch = adapter.scanNext(
            new ScanCursor(chain, "primary", null, 4),
            new WatchSnapshot(0, List.of(new WatchTarget(watched, "owner-1", "account-1"))),
            new ChainPoint("", "10", block, List.of(), null));

        assertGoFixture("evm-native-token.json", batch);
        }

        @Test
        void solanaBatchMatchesGoFixture() throws IOException {
        String genesis = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1";
        String wallet = "Wallet1111111111111111111111111111111111111";
        String tokenAccount = "TokenAccount1111111111111111111111111111111";
        String token2022Account = "Token2022Account11111111111111111111111111";
        String mint = "Mint11111111111111111111111111111111111111";
        String mint2022 = "Mint2022111111111111111111111111111111111";
        RpcTransport rpc = responses("[42]", """
            {"blockhash":"Blockhash42","previousBlockhash":"Blockhash40","parentSlot":40,
             "blockTime":1700000000,"transactions":[{"transaction":{"signatures":["SignatureSuccess"],
             "message":{"accountKeys":["Sender","%s","%s","%s"],"instructions":[{},
               {"programId":"11111111111111111111111111111111","parsed":{"type":"transfer","info":{"source":"Sender","destination":"%s"}}},
               {"programId":"%s","parsed":{"type":"transferChecked","info":{"source":"SourceToken","destination":"%s","authority":"Sender","mint":"%s"}}}] }},
             "meta":{"err":null,"preBalances":[2000000000,100000000,2039280,2039280],
               "postBalances":[1499995000,600000000,2039280,2039280],
               "preTokenBalances":[
                 {"accountIndex":2,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"10","decimals":6}},
                 {"accountIndex":3,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"20","decimals":2}}],
               "postTokenBalances":[
                 {"accountIndex":2,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"1000010","decimals":6}},
                 {"accountIndex":3,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"119","decimals":2}}],
               "innerInstructions":[{"index":3,"instructions":[{},
                 {"programId":"%s","parsed":{"type":"transferChecked","info":{"source":"Source2022","destination":"%s","authority":"Sender","mint":"%s"}}}]}]}}]}
            """.formatted(
            wallet, tokenAccount, token2022Account, wallet,
            SolanaScanAdapter.TOKEN_PROGRAM, tokenAccount, mint,
            mint, wallet, SolanaScanAdapter.TOKEN_PROGRAM,
            mint2022, wallet, SolanaScanAdapter.TOKEN_2022_PROGRAM,
            mint, wallet, SolanaScanAdapter.TOKEN_PROGRAM,
            mint2022, wallet, SolanaScanAdapter.TOKEN_2022_PROGRAM,
            SolanaScanAdapter.TOKEN_2022_PROGRAM, token2022Account, mint2022));
        SolanaScanAdapter adapter = new SolanaScanAdapter(
            genesis,
            42,
            rpc,
            (chain, position) -> Optional.empty(),
            List.of(
                new SolanaScanAdapter.AssetConfig(SolanaScanAdapter.TOKEN_PROGRAM, mint, 6),
                new SolanaScanAdapter.AssetConfig(SolanaScanAdapter.TOKEN_2022_PROGRAM, mint2022, 2)),
            Map.of(tokenAccount, wallet, token2022Account, wallet));
        ChainRef chain = ChainRef.parse("solana:" + genesis);

        ScanBatch batch = adapter.scanNext(
            new ScanCursor(chain, "primary", null, 3),
            new WatchSnapshot(0, List.of(
                new WatchTarget(wallet, "owner-sol", "account-sol"),
                new WatchTarget(tokenAccount, "owner-spl", "account-spl"),
                new WatchTarget(token2022Account, "owner-2022", "account-2022"))),
            new ChainPoint("", "42", "Blockhash42", List.of(), null));

        assertGoFixture("solana-sol-token.json", batch);
        }

        @Test
        void tonBatchMatchesGoFixture() throws IOException {
        ToncenterApi api = new CompatibilityToncenterApi();
        TonScanAdapter adapter = new TonScanAdapter(
            "testnet",
            101,
            api,
            (chain, position) -> Optional.empty(),
            List.of(new TonScanAdapter.JettonConfig("EQMaster", 6)));
        ChainRef chain = ChainRef.parse("ton:testnet");

        ScanBatch batch = adapter.scanNext(
            new ScanCursor(chain, "primary", null, 4),
            new WatchSnapshot(0, List.of(new WatchTarget("wallet", "owner", "account"))),
            new ChainPoint("masterchain", "101", "root101", List.of(), null));

        assertGoFixture("ton-native-jetton-parents.json", batch);
        }

    private static void assertGoFixture(String name, ScanBatch batch) throws IOException {
        JsonNode expected;
        try (var stream = GoBatchCompatibilityTest.class.getResourceAsStream("/compatibility/" + name)) {
            if (stream == null) {
                throw new AssertionError("Missing compatibility fixture: " + name);
            }
            expected = MAPPER.readTree(stream);
        }
        assertJsonEquals(name, expected, goFields(MAPPER.valueToTree(batch)));
    }

    private static void assertJsonEquals(String path, JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path);
            return;
        }
        assertEquals(expected.getNodeType(), actual.getNodeType(), path + " node type");
        if (expected.isObject()) {
            LinkedHashSet<String> expectedFields = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            LinkedHashSet<String> actualFields = new LinkedHashSet<>();
            actual.fieldNames().forEachRemaining(actualFields::add);
            assertEquals(expectedFields, actualFields, path + " fields");
            expectedFields.forEach(field ->
                    assertJsonEquals(path + "." + field, expected.get(field), actual.get(field)));
        } else if (expected.isArray()) {
            assertEquals(expected.size(), actual.size(), path + " size");
            for (int index = 0; index < expected.size(); index++) {
                assertJsonEquals(path + "[" + index + "]", expected.get(index), actual.get(index));
            }
        } else {
            assertEquals(expected, actual, path);
        }
    }

    private static JsonNode goFields(JsonNode value) {
        if (value.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            value.forEach(element -> result.add(goFields(element)));
            return result;
        }
        if (!value.isObject()) {
            return value;
        }
        ObjectNode result = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        Map<String, JsonNode> ordered = new LinkedHashMap<>();
        fields.forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
        ordered.forEach((name, child) -> result.set(GO_FIELDS.getOrDefault(name, name), goFields(child)));
        return result;
    }

    private static RpcTransport responses(String... values) {
        Queue<JsonNode> responses = new ArrayDeque<>();
        for (String value : values) {
            try {
                responses.add(MAPPER.readTree(value));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException(error);
            }
        }
        return (method, parameters) -> {
            JsonNode response = responses.poll();
            if (response == null) {
                throw new AssertionError("Unexpected RPC call: " + method);
            }
            return response;
        };
    }

    private static String hash(int value) {
        return "%064x".formatted(value);
    }

    private static String jsonString(String value) {
        return "\"" + value + "\"";
    }

    private static String transferTopic() {
        return "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + address.substring(2);
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private static final class CompatibilityToncenterApi implements ToncenterApi {

        private final Map<String, JsonNode> blocks = Map.of(
                "-1:-9223372036854775808:101", json("""
                        {"workchain":-1,"shard":"-9223372036854775808","seqno":101,
                         "root_hash":"root101","gen_utime":"1700000000","prev_blocks":[
                           {"workchain":-1,"shard":"-9223372036854775808","seqno":100},
                           {"workchain":0,"shard":"8000000000000000","seqno":70}]}
                        """),
                "-1:-9223372036854775808:100", json("""
                        {"workchain":-1,"shard":"-9223372036854775808","seqno":100,
                         "root_hash":"root100","gen_utime":"1699999999","prev_blocks":[]}
                        """),
                "0:8000000000000000:70", json("""
                        {"workchain":0,"shard":"8000000000000000","seqno":70,
                         "root_hash":"shard70","gen_utime":"1699999999","prev_blocks":[]}
                        """));

        @Override
        public JsonNode masterchainInfo() {
            return json("{\"last\":{\"seqno\":101,\"root_hash\":\"root101\"}}");
        }

        @Override
        public JsonNode block(long workchain, String shard, long seqno) {
            JsonNode block = blocks.get(workchain + ":" + shard + ":" + seqno);
            if (block == null) {
                throw new AssertionError("Unexpected TON block request");
            }
            return block;
        }

        @Override
        public List<JsonNode> transactions(long masterchainSeqno) {
            return List.of(
                    json("""
                            {"hash":"tx-native","description":{"aborted":false},"in_msg":{
                             "hash":"msg-native","source":"sender","destination":"wallet",
                             "value":"1200000000","bounced":false}}
                            """),
                    json("{\"hash\":\"tx-jetton\",\"description\":{\"aborted\":false},\"in_msg\":{\"hash\":\"msg-jetton\"}}"),
                    json("""
                            {"hash":"tx-bounced","description":{"aborted":false},"in_msg":{
                             "hash":"msg-bounced","destination":"wallet","value":"9","bounced":true}}
                            """),
                    json("{\"hash\":\"tx-fake\",\"description\":{\"aborted\":false},\"in_msg\":{\"hash\":\"msg-fake\"}}"));
        }

        @Override
        public List<JsonNode> jettonTransfers(long blockTime) {
            return List.of(
                    jetton("tx-jetton", "1", "EQMaster", "2500000"),
                    jetton("tx-jetton", "2", "EQMaster", "3000000"),
                    jetton("tx-fake", "", "EQFake", "7"));
        }

        private static JsonNode jetton(String transaction, String queryId, String master, String amount) {
            return json("""
                    {"transaction_hash":"%s","transaction_aborted":false,"query_id":"%s",
                     "jetton_master":"%s","source_wallet":"EQJettonWallet","source":"sender",
                     "destination":"wallet","amount":"%s"}
                    """.formatted(transaction, queryId, master, amount));
        }
    }
}