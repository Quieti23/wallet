package io.quieti.wallet.adapter.node;

import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import java.util.Map;

public final class RoutingNodeAdapter implements NodePort {

    private final Map<String, NodePort> adapters;

    public RoutingNodeAdapter(Map<String, NodePort> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    @Override
    public ChainCheckpoint initialCheckpoint(String chain) {
        return adapter(chain).initialCheckpoint(chain);
    }

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        return adapter(chain).fetchBlock(chain, height);
    }

    private NodePort adapter(String chain) {
        NodePort adapter = adapters.get(chain);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported chain: " + chain);
        }
        return adapter;
    }
}