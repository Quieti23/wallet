package io.quieti.wallet.adapter.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.port.BalancePort;
import io.quieti.wallet.application.port.TransactionBuilderPort;
import io.quieti.wallet.application.transfer.BalanceSnapshot;
import io.quieti.wallet.application.transfer.TransferRequest;
import io.quieti.wallet.application.transfer.UnsignedTransaction;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BitcoinWalletAdapter implements BalancePort, TransactionBuilderPort {

    private static final int DECIMALS = 8;

    private final String chain;
    private final RpcTransport rpc;

    public BitcoinWalletAdapter(String chain, RpcTransport rpc) {
        if (!"BTC_TESTNET".equals(chain) && !"BTC_SIGNET".equals(chain)) {
            throw new IllegalArgumentException("Bitcoin wallet is restricted to Testnet or Signet");
        }
        this.chain = chain;
        this.rpc = Objects.requireNonNull(rpc, "rpc");
    }

    @Override
    public BalanceSnapshot queryBalance(String requestedChain) {
        requireChain(requestedChain);
        JsonNode mine = rpc.call("getbalances").path("mine");
        return new BalanceSnapshot(
                chain,
                "BTC",
                satoshis(mine.get("trusted"), "trusted balance"),
                satoshis(mine.get("untrusted_pending"), "pending balance"),
                satoshis(mine.get("immature"), "immature balance"),
                DECIMALS);
    }

    @Override
    public UnsignedTransaction buildTransfer(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        requireChain(request.chain());
        BigDecimal amount = new BigDecimal(request.amountAtomicUnits(), DECIMALS);
        JsonNode result = rpc.call(
                "walletcreatefundedpsbt",
                List.of(),
                Map.of(request.toAddress(), amount),
                0,
                Map.of("add_inputs", true, "lockUnspents", true),
                true);
        JsonNode psbt = result.get("psbt");
        if (psbt == null || !psbt.isTextual() || psbt.textValue().isBlank()) {
            throw new IllegalArgumentException("Bitcoin Core returned an invalid PSBT");
        }
        int changePosition = result.path("changepos").asInt(-2);
        return new UnsignedTransaction(
                chain,
                "PSBT",
                psbt.textValue(),
                satoshis(result.get("fee"), "transaction fee"),
                changePosition);
    }

    private void requireChain(String requestedChain) {
        if (!chain.equals(requestedChain)) {
            throw new IllegalArgumentException("Unsupported chain: " + requestedChain);
        }
    }

    private static BigInteger satoshis(JsonNode value, String field) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Bitcoin Core " + field + " must be numeric");
        }
        try {
            BigInteger atomicUnits = value.decimalValue().movePointRight(DECIMALS).toBigIntegerExact();
            if (atomicUnits.signum() < 0) {
                throw new IllegalArgumentException("Bitcoin Core " + field + " must not be negative");
            }
            return atomicUnits;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Bitcoin Core " + field + " has more than eight decimals", exception);
        }
    }
}