package io.quieti.wallet.adapter.node;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import java.util.Map;

public final class SolanaNodeAdapter extends AbstractRpcNodeAdapter {

    private final RpcTransport rpc;
    private final int maxSkippedSlots;

    public SolanaNodeAdapter(ChainCheckpoint initialCheckpoint, RpcTransport rpc, int maxSkippedSlots) {
        super("SOL", initialCheckpoint);
        this.rpc = rpc;
        if (maxSkippedSlots < 0) {
            throw new IllegalArgumentException("maxSkippedSlots must not be negative");
        }
        this.maxSkippedSlots = maxSkippedSlots;
    }

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        requireChain(chain);
        for (long slot = height; slot <= height + maxSkippedSlots; slot++) {
            JsonNode block = rpc.call("getBlock", slot, Map.of(
                    "commitment", "finalized",
                    "transactionDetails", "none",
                    "rewards", false,
                    "maxSupportedTransactionVersion", 0));
            if (!block.isNull()) {
                return new ChainBlock(chain, slot,
                        requiredText(block, "blockhash"),
                        requiredText(block, "previousBlockhash"));
            }
        }
        throw new IllegalStateException("No finalized Solana block in configured slot window");
    }
}