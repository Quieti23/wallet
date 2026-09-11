package io.quieti.wallet.application.transfer;

import io.quieti.wallet.application.port.TransactionBuilderPort;
import java.util.Locale;
import java.util.Objects;

public final class BuildTransferUseCase {

    private final TransactionBuilderPort transactionBuilderPort;

    public BuildTransferUseCase(TransactionBuilderPort transactionBuilderPort) {
        this.transactionBuilderPort = Objects.requireNonNull(
                transactionBuilderPort, "transactionBuilderPort");
    }

    public UnsignedTransaction build(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        return transactionBuilderPort.buildTransfer(new TransferRequest(
                request.chain().toUpperCase(Locale.ROOT),
                request.toAddress(),
                request.amountAtomicUnits()));
    }
}