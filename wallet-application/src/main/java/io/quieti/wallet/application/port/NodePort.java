package io.quieti.wallet.application.port;

import io.quieti.wallet.domain.chain.ChainBlock;

public interface NodePort {

    ChainBlock fetchBlock(String chain, long height);
}