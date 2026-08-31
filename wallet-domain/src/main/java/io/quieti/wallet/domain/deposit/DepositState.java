package io.quieti.wallet.domain.deposit;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum DepositState {
    OBSERVED,
    CONFIRMING,
    FINALITY_READY,
    CREDITED,
    REORGED,
    REVERSAL_PENDING,
    REVERSED;

    private static final Map<DepositState, Set<DepositState>> TRANSITIONS = Map.of(
            OBSERVED, EnumSet.of(CONFIRMING, REORGED),
            CONFIRMING, EnumSet.of(FINALITY_READY, REORGED),
            FINALITY_READY, EnumSet.of(CREDITED, REORGED),
            CREDITED, EnumSet.of(REVERSAL_PENDING),
            REORGED, EnumSet.of(CONFIRMING),
            REVERSAL_PENDING, EnumSet.of(REVERSED),
            REVERSED, EnumSet.noneOf(DepositState.class));

    public boolean canTransitionTo(DepositState target) {
        return TRANSITIONS.get(this).contains(target);
    }

    public DepositState transitionTo(DepositState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("invalid deposit transition: " + this + " -> " + target);
        }
        return target;
    }
}