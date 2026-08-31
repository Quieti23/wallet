package io.quieti.wallet.adapter.node;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.domain.chain.ChainCheckpoint;

abstract class AbstractRpcNodeAdapter implements NodePort {

    private final String chain;
    private final ChainCheckpoint initialCheckpoint;

    AbstractRpcNodeAdapter(String chain, ChainCheckpoint initialCheckpoint) {
        this.chain = chain;
        this.initialCheckpoint = initialCheckpoint;
    }

    @Override
    public final ChainCheckpoint initialCheckpoint(String requestedChain) {
        requireChain(requestedChain);
        return initialCheckpoint;
    }

    final void requireChain(String requestedChain) {
        if (!chain.equals(requestedChain)) {
            throw new IllegalArgumentException("Adapter for " + chain + " cannot scan " + requestedChain);
        }
    }

    final String chainId() {
        return chain;
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing RPC text field: " + field);
        }
        return value.textValue();
    }
}