package io.quieti.wallet.application.port;

import io.quieti.wallet.application.transfer.TransferRequest;
import io.quieti.wallet.application.transfer.UnsignedTransaction;

public interface TransactionBuilderPort {

    UnsignedTransaction buildTransfer(TransferRequest request);
}