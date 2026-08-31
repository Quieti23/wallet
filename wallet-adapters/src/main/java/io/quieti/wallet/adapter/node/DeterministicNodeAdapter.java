package io.quieti.wallet.adapter.node;

import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.domain.chain.ChainBlock;

public final class DeterministicNodeAdapter implements NodePort {

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        return new ChainBlock(chain, height, hash(chain, height), hash(chain, height - 1));
    }

    public static String hash(String chain, long height) {
        return chain.toLowerCase() + "-block-" + height;
    }
}