package io.quieti.wallet.application.scan;

import io.quieti.wallet.application.port.CanonicalReader;
import io.quieti.wallet.domain.deposit.DepositObservation;

public interface ShadowReferenceReader extends CanonicalReader {

    boolean matchesObservation(DepositObservation observation);
}