package io.quieti.wallet.application.port;

import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;

public interface NodePort {

    ChainCheckpoint initialCheckpoint(String chain);

    ChainBlock fetchBlock(String chain, long height);
}