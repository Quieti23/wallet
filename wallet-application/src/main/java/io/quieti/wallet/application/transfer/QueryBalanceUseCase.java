package io.quieti.wallet.application.transfer;

import io.quieti.wallet.application.port.BalancePort;
import java.util.Locale;
import java.util.Objects;

public final class QueryBalanceUseCase {

    private final BalancePort balancePort;

    public QueryBalanceUseCase(BalancePort balancePort) {
        this.balancePort = Objects.requireNonNull(balancePort, "balancePort");
    }

    public BalanceSnapshot query(String chain) {
        Objects.requireNonNull(chain, "chain");
        return balancePort.queryBalance(chain.toUpperCase(Locale.ROOT));
    }
}