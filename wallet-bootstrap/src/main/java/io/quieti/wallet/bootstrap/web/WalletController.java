package io.quieti.wallet.bootstrap.web;

import io.quieti.wallet.application.transfer.BalanceSnapshot;
import io.quieti.wallet.application.transfer.BuildTransferUseCase;
import io.quieti.wallet.application.transfer.QueryBalanceUseCase;
import io.quieti.wallet.application.transfer.TransferRequest;
import io.quieti.wallet.application.transfer.UnsignedTransaction;
import java.math.BigInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "wallet.rpc.enabled", havingValue = "true")
public class WalletController {

    private final QueryBalanceUseCase queryBalanceUseCase;
    private final BuildTransferUseCase buildTransferUseCase;

    public WalletController(
            QueryBalanceUseCase queryBalanceUseCase,
            BuildTransferUseCase buildTransferUseCase) {
        this.queryBalanceUseCase = queryBalanceUseCase;
        this.buildTransferUseCase = buildTransferUseCase;
    }

    @GetMapping("/balances/{chain}")
    public BalanceResponse queryBalance(@PathVariable("chain") String chain) {
        BalanceSnapshot balance = queryBalanceUseCase.query(chain);
        return new BalanceResponse(
                balance.chain(),
                balance.asset(),
                balance.availableAtomicUnits().toString(),
                balance.pendingAtomicUnits().toString(),
                balance.immatureAtomicUnits().toString(),
                balance.decimals());
    }

    @PostMapping("/transfers/build")
    public TransactionResponse buildTransfer(@RequestBody BuildTransferRequest request) {
        UnsignedTransaction transaction = buildTransferUseCase.build(new TransferRequest(
                request.chain(),
                request.toAddress(),
                new BigInteger(request.amountAtomicUnits())));
        return new TransactionResponse(
                transaction.chain(),
                transaction.format(),
                transaction.payload(),
                transaction.feeAtomicUnits().toString(),
                transaction.changePosition());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidRequest(IllegalArgumentException exception) {
        return new ErrorResponse(exception.getMessage());
    }

        public record BuildTransferRequest(String chain, String toAddress, String amountAtomicUnits) {

                public BuildTransferRequest {
                        if (chain == null || chain.isBlank()
                                        || toAddress == null || toAddress.isBlank()
                                        || amountAtomicUnits == null || amountAtomicUnits.isBlank()) {
                                throw new IllegalArgumentException(
                                                "chain, destination address and atomic amount are required");
                        }
                }
        }

    public record BalanceResponse(
            String chain,
            String asset,
            String availableAtomicUnits,
            String pendingAtomicUnits,
            String immatureAtomicUnits,
            int decimals) {}

    public record TransactionResponse(
            String chain,
            String format,
            String payload,
            String feeAtomicUnits,
            int changePosition) {}

    public record ErrorResponse(String message) {}
}