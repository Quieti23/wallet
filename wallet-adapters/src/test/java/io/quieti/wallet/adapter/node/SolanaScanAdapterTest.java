package io.quieti.wallet.adapter.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class SolanaScanAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GENESIS = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1";
    private static final String WALLET = "Wallet1111111111111111111111111111111111111";
    private static final String TOKEN_ACCOUNT = "TokenAccount1111111111111111111111111111111";
    private static final String MINT = "Mint11111111111111111111111111111111111111";
    private static final ChainRef CHAIN = ChainRef.parse("solana:" + GENESIS);

    @Test
    void skipsEmptySlotsAndEmitsBalanceBackedSolAndSpl() {
        RecordingTransport rpc = responses("[42]", parsedBlock());
        SolanaScanAdapter adapter = adapter(rpc);
    ChainPoint cursorPoint = new ChainPoint("", "40", "Blockhash40", List.of(), null);

        ScanBatch batch = adapter.scanNext(
                new ScanCursor(CHAIN, "deposit", cursorPoint, 3),
                new WatchSnapshot(0, List.of(
                        new WatchTarget(WALLET, "owner-sol", "account-sol"),
                        new WatchTarget(TOKEN_ACCOUNT, "owner-spl", "account-spl"))),
                new ChainPoint("", "42", "Blockhash42", List.of(), null));

        assertEquals("42", batch.nextCursor().point().position());
        assertEquals(4, batch.nextCursor().version());
        assertEquals(List.of("instruction:0", "instruction:1"), batch.observations().stream()
                .map(observation -> observation.id().source()).toList());
        assertEquals(List.of("500", "1000"), batch.observations().stream()
                .map(observation -> observation.amount().raw()).toList());
        assertEquals("spl", batch.observations().get(1).asset().standard());
        assertEquals(MINT, batch.observations().get(1).metadata().get("mint"));
        assertEquals("WalletAuthority", batch.observations().get(1).metadata().get("authority"));
        assertEquals(List.of("getBlocks", "getBlock"), rpc.methods);
    }

    @Test
    void resolvesConfirmedAndFinalizedHeads() {
        RecordingTransport rpc = responses("42", headBlock(42), "40", headBlock(40));
        SolanaScanAdapter adapter = adapter(rpc);
        FinalityPolicy policy = new FinalityPolicy() {
            @Override
            public FinalityTarget observationTarget(ChainRef chain) {
                return new FinalityTarget("commitment", "confirmed");
            }

            @Override
            public FinalityTarget finalityTarget(ChainRef chain) {
                return new FinalityTarget("commitment", "finalized");
            }
        };

        assertEquals("42", adapter.observationHead(policy).position());
        assertEquals("40", adapter.finalityHead(policy).position());
        assertEquals(List.of("getSlot", "getBlock", "getSlot", "getBlock"), rpc.methods);
    }

    private static SolanaScanAdapter adapter(RpcTransport rpc) {
        return new SolanaScanAdapter(
                GENESIS,
                42,
                rpc,
                (chain, position) -> Optional.empty(),
                List.of(new SolanaScanAdapter.AssetConfig(
                        SolanaScanAdapter.TOKEN_PROGRAM, MINT, 6)),
                Map.of(TOKEN_ACCOUNT, WALLET));
    }

    private static String parsedBlock() {
        return """
                {
                  "blockhash":"Blockhash42","previousBlockhash":"Blockhash40","parentSlot":40,"blockTime":1700000000,
                  "transactions":[
                    {
                      "transaction":{"signatures":["SignatureSuccess"],"message":{
                        "accountKeys":["Sender","%s","SourceToken","%s"],
                        "instructions":[
                          {"programId":"11111111111111111111111111111111","parsed":{"type":"transfer","info":{"source":"Sender","destination":"%s"}}},
                          {"programId":"%s","parsed":{"type":"transferChecked","info":{"source":"SourceToken","destination":"%s","authority":"WalletAuthority","mint":"%s"}}}
                        ]}},
                      "meta":{"err":null,"preBalances":[2000,100,0,2039280],"postBalances":[1490,600,0,2039280],
                        "preTokenBalances":[{"accountIndex":3,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"10","decimals":6}}],
                        "postTokenBalances":[{"accountIndex":3,"mint":"%s","owner":"%s","programId":"%s","uiTokenAmount":{"amount":"1010","decimals":6}}],
                        "innerInstructions":[]}
                    },
                    {
                      "transaction":{"signatures":["SignatureFailed"],"message":{"accountKeys":["Sender","%s"],"instructions":[]}},
                      "meta":{"err":{"InstructionError":[0,"Failed"]},"preBalances":[1,1],"postBalances":[0,2]}
                    }
                  ]
                }
                """.formatted(
                WALLET,
                TOKEN_ACCOUNT,
                WALLET,
                SolanaScanAdapter.TOKEN_PROGRAM,
                TOKEN_ACCOUNT,
                MINT,
                MINT,
                WALLET,
                SolanaScanAdapter.TOKEN_PROGRAM,
                MINT,
                WALLET,
                SolanaScanAdapter.TOKEN_PROGRAM,
                WALLET);
    }

    private static String headBlock(long slot) {
        return """
                {"blockhash":"Blockhash%s","previousBlockhash":"Blockhash%s","parentSlot":%s,"blockTime":1,"transactions":[]}
                """.formatted(slot, slot - 1, slot - 1);
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
            JsonNode result = responses.poll();
            if (result == null) {
                throw new AssertionError("Unexpected RPC call: " + method);
            }
            return result;
        }
    }
}
