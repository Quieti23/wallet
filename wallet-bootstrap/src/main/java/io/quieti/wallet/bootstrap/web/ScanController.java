package io.quieti.wallet.bootstrap.web;

import io.quieti.wallet.application.scan.ScanBlockUseCase;
import io.quieti.wallet.bootstrap.config.WalletProperties;
import io.quieti.wallet.domain.chain.ChainBlock;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

    private final ScanBlockUseCase scanBlockUseCase;
    private final WalletProperties properties;
    private final ExecutorService scanExecutor;

    public ScanController(
            ScanBlockUseCase scanBlockUseCase,
            WalletProperties properties,
            ExecutorService scanExecutor) {
        this.scanBlockUseCase = scanBlockUseCase;
        this.properties = properties;
        this.scanExecutor = scanExecutor;
    }

    @PostMapping("/{chain}/{partitionId}/next")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<ChainBlock> scanNext(
            @PathVariable("chain") String chain,
            @PathVariable("partitionId") String partitionId) {
        String normalizedChain = chain.toUpperCase(Locale.ROOT);
        return CompletableFuture.supplyAsync(
                () -> scanBlockUseCase.scanNext(
                        partitionId,
                        normalizedChain,
                        properties.scan().ownerId()),
                scanExecutor);
    }
}