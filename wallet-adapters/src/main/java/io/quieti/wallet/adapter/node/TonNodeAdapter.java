package io.quieti.wallet.adapter.node;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import java.util.Map;

public final class TonNodeAdapter extends AbstractRpcNodeAdapter {

    private static final int MASTERCHAIN_WORKCHAIN = -1;
    private static final String MASTERCHAIN_SHARD = "-9223372036854775808";

    private final RpcTransport rpc;

    public TonNodeAdapter(ChainCheckpoint initialCheckpoint, RpcTransport rpc) {
        super("TON", initialCheckpoint);
        this.rpc = rpc;
    }

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        requireChain(chain);
        Map<String, Object> lookup = Map.of(
                "workchain", MASTERCHAIN_WORKCHAIN,
                "shard", MASTERCHAIN_SHARD,
                "seqno", height);
        JsonNode id = rpc.call("lookupBlock", lookup);
        if (id.path("seqno").asLong(-1) != height || id.path("workchain").asInt() != MASTERCHAIN_WORKCHAIN) {
            throw new IllegalArgumentException("TON masterchain block does not match requested seqno");
        }
        String hash = requiredText(id, "root_hash");
        JsonNode header = rpc.call("getBlockHeader", Map.of(
                "workchain", MASTERCHAIN_WORKCHAIN,
                "shard", requiredText(id, "shard"),
                "seqno", height,
                "root_hash", hash,
                "file_hash", requiredText(id, "file_hash")));
        JsonNode previousBlocks = header.get("prev_blocks");
        if (previousBlocks == null || !previousBlocks.isArray() || previousBlocks.isEmpty()) {
            throw new IllegalArgumentException("TON block header has no previous block");
        }
        return new ChainBlock(chain, height, hash, requiredText(previousBlocks.get(0), "root_hash"));
    }
}