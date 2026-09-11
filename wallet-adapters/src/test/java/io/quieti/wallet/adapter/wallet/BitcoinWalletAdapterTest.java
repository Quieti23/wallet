package io.quieti.wallet.adapter.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.transfer.BalanceSnapshot;
import io.quieti.wallet.application.transfer.TransferRequest;
import io.quieti.wallet.application.transfer.UnsignedTransaction;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class BitcoinWalletAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void queriesWalletBalanceInSatoshis() {
        RecordingTransport rpc = responses("""
                {"mine":{"trusted":1.23456789,"untrusted_pending":0.00000002,"immature":0.5}}
                """);
        BitcoinWalletAdapter adapter = new BitcoinWalletAdapter("BTC_SIGNET", rpc);

        BalanceSnapshot balance = adapter.queryBalance("BTC_SIGNET");

        assertEquals(new BigInteger("123456789"), balance.availableAtomicUnits());
        assertEquals(BigInteger.TWO, balance.pendingAtomicUnits());
        assertEquals(new BigInteger("50000000"), balance.immatureAtomicUnits());
        assertEquals(List.of("getbalances"), rpc.methods);
    }

    @Test
    void buildsFundedPsbtAndLocksSelectedInputs() {
        RecordingTransport rpc = responses("""
                {"psbt":"cHNidP8BAHECAAAAAQ==","fee":0.00000123,"changepos":1}
                """);
        BitcoinWalletAdapter adapter = new BitcoinWalletAdapter("BTC_TESTNET", rpc);

        UnsignedTransaction transaction = adapter.buildTransfer(new TransferRequest(
                "BTC_TESTNET",
                "tb1qdestination",
                new BigInteger("125000000")));

        assertEquals("PSBT", transaction.format());
        assertEquals("cHNidP8BAHECAAAAAQ==", transaction.payload());
        assertEquals(new BigInteger("123"), transaction.feeAtomicUnits());
        assertEquals(1, transaction.changePosition());
        assertEquals("walletcreatefundedpsbt", rpc.methods.getFirst());
        assertEquals(List.of(), rpc.parameters.getFirst()[0]);
        assertEquals(Map.of("tb1qdestination", new BigDecimal("1.25000000")), rpc.parameters.getFirst()[1]);
        assertEquals(Map.of("add_inputs", true, "lockUnspents", true), rpc.parameters.getFirst()[3]);
    }

    @Test
    void rejectsMainnetAndMismatchedChains() {
        RecordingTransport rpc = responses();

        assertThrows(IllegalArgumentException.class,
                () -> new BitcoinWalletAdapter("BTC_MAINNET", rpc));
        BitcoinWalletAdapter adapter = new BitcoinWalletAdapter("BTC_SIGNET", rpc);
        assertThrows(IllegalArgumentException.class, () -> adapter.queryBalance("BTC_TESTNET"));
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
        private final List<Object[]> parameters = new ArrayList<>();

        private RecordingTransport(Queue<JsonNode> responses) {
            this.responses = responses;
        }

        @Override
        public JsonNode call(String method, Object... parameters) {
            methods.add(method);
            this.parameters.add(parameters);
            JsonNode response = responses.poll();
            if (response == null) {
                throw new AssertionError("Unexpected RPC call: " + method);
            }
            return response;
        }
    }
}