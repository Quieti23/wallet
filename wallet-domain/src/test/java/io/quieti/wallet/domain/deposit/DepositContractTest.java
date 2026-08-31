package io.quieti.wallet.domain.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quieti.wallet.domain.chain.Amount;
import io.quieti.wallet.domain.chain.AssetRef;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DepositContractTest {

    @Test
    void preservesGoCompatibleObservationIdentity() {
        ChainRef chain = ChainRef.parse("bip122:signet");
        ObservationId id = new ObservationId(
                chain,
                new TransactionRef("hash", "txid"),
                "vout:2");
        DepositObservation observation = new DepositObservation(
                id,
                new AssetRef(chain, "native", "btc"),
                "",
                "0014script",
                new Amount("1200", 8),
                new ChainPoint("", "100", "block-hash", List.of(), null),
                Map.of("owner_id", "owner-1", "account_id", "account-1"));

        assertEquals("bip122:signet/hash:txid/vout:2", observation.id().toString());
        assertEquals("1200", observation.amount().raw());
    }

    @Test
    void enforcesGoStateTransitions() {
        assertTrue(DepositState.OBSERVED.canTransitionTo(DepositState.CONFIRMING));
        assertTrue(DepositState.CREDITED.canTransitionTo(DepositState.REVERSAL_PENDING));
        assertFalse(DepositState.CREDITED.canTransitionTo(DepositState.REORGED));
        assertThrows(IllegalStateException.class,
                () -> DepositState.REVERSED.transitionTo(DepositState.CONFIRMING));
    }
}