package io.quieti.wallet.application.port;

import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.Optional;

public interface CanonicalReader {

    Optional<ChainPoint> canonicalPoint(ChainRef chain, String position);
}