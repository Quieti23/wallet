package io.quieti.wallet.application.port;

import io.quieti.wallet.application.transfer.BalanceSnapshot;

public interface BalancePort {

    BalanceSnapshot queryBalance(String chain);
}