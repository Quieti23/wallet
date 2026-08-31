package io.quieti.wallet.adapter.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SolanaRpcPool implements RpcTransport {

    public record Node(String name, RpcTransport transport) {
        public Node {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Solana RPC node name is required");
            }
            Objects.requireNonNull(transport, "transport");
        }
    }

    private static final class State {
        private final Node node;
        private long slot;
        private boolean hasSlot;
        private int failures;
        private Instant cooldownUntil = Instant.EPOCH;

        private State(Node node) {
            this.node = node;
        }
    }

    private final List<State> states;
    private final long maxSlotLag;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Clock clock;
    private long bestSlot;

    public SolanaRpcPool(
            List<Node> nodes,
            long maxSlotLag,
            Duration baseBackoff,
            Duration maxBackoff) {
        this(nodes, maxSlotLag, baseBackoff, maxBackoff, Clock.systemUTC());
    }

    SolanaRpcPool(
            List<Node> nodes,
            long maxSlotLag,
            Duration baseBackoff,
            Duration maxBackoff,
            Clock clock) {
        if (nodes == null || nodes.isEmpty() || maxSlotLag <= 0) {
            throw new IllegalArgumentException("Solana RPC nodes and positive max slot lag are required");
        }
        this.baseBackoff = requirePositive(baseBackoff, "baseBackoff");
        this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than baseBackoff");
        }
        long uniqueNames = nodes.stream().map(Node::name).distinct().count();
        if (uniqueNames != nodes.size()) {
            throw new IllegalArgumentException("Solana RPC node names must be unique");
        }
        this.states = nodes.stream().map(State::new).toList();
        this.maxSlotLag = maxSlotLag;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public JsonNode call(String method, Object... parameters) {
        return method.equals("getSlot")
                ? highestSlot(method, parameters)
                : failover(method, parameters);
    }

    private JsonNode highestSlot(String method, Object[] parameters) {
        List<State> available = availableStates();
        RuntimeException failure = null;
        JsonNode highest = null;
        for (State state : available) {
            try {
                JsonNode result = state.node.transport().call(method, parameters);
                long slot = result.longValue();
                recordSuccess(state, slot);
                if (highest == null || slot > highest.longValue()) {
                    highest = result;
                }
            } catch (RuntimeException exception) {
                recordFailure(state, exception);
                failure = appendFailure(failure, state, exception);
            }
        }
        if (highest != null) {
            return highest;
        }
        throw failure == null
                ? new RpcException("solana failover: all nodes are cooling down")
                : failure;
    }

    private JsonNode failover(String method, Object[] parameters) {
        long targetSlot = parameters.length > 0 && parameters[0] instanceof Number number
                ? number.longValue()
                : 0;
        RuntimeException failure = null;
        for (State state : rankedStates(targetSlot)) {
            try {
                JsonNode result = state.node.transport().call(method, parameters);
                recordSuccess(state, observedSlot(method, targetSlot, result));
                return result;
            } catch (RuntimeException exception) {
                recordFailure(state, exception);
                failure = appendFailure(failure, state, exception);
            }
        }
        throw failure == null
                ? new RpcException("solana failover: no healthy node is available")
                : failure;
    }

    private synchronized List<State> availableStates() {
        Instant now = clock.instant();
        return states.stream().filter(state -> !state.cooldownUntil.isAfter(now)).toList();
    }

    private synchronized List<State> rankedStates(long targetSlot) {
        Instant now = clock.instant();
        List<State> result = states.stream()
                .filter(state -> !state.cooldownUntil.isAfter(now))
                .filter(state -> !state.hasSlot
                        || bestSlot - Math.min(bestSlot, state.slot) <= maxSlotLag && state.slot >= targetSlot)
                .sorted(Comparator.comparingLong(this::score).reversed())
                .toList();
        if (!result.isEmpty()) {
            return result;
        }
        return states.stream()
                .filter(state -> !state.cooldownUntil.isAfter(now))
                .sorted(Comparator.comparingLong(this::score).reversed())
                .toList();
    }

    private synchronized void recordSuccess(State state, long slot) {
        state.failures = 0;
        state.cooldownUntil = Instant.EPOCH;
        if (slot > 0) {
            state.slot = slot;
            state.hasSlot = true;
            bestSlot = Math.max(bestSlot, slot);
        }
    }

    private synchronized void recordFailure(State state, RuntimeException exception) {
        state.failures++;
        Duration delay = exponentialBackoff(state.failures);
        RpcRateLimitException limited = findRateLimit(exception);
        if (limited != null && limited.retryAfter().compareTo(delay) > 0) {
            delay = limited.retryAfter();
        }
        state.cooldownUntil = clock.instant().plus(delay);
    }

    private long score(State state) {
        long lag = state.hasSlot ? bestSlot - Math.min(bestSlot, state.slot) : 0;
        return 100 - lag * 5 - state.failures * 20L - (state.hasSlot ? 0 : 25);
    }

    private Duration exponentialBackoff(int failures) {
        Duration delay = baseBackoff;
        for (int attempt = 1; attempt < failures && delay.compareTo(maxBackoff) < 0; attempt++) {
            delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private static long observedSlot(String method, long targetSlot, JsonNode result) {
        if (method.equals("getBlocks") && result.isArray() && !result.isEmpty()) {
            return result.get(result.size() - 1).longValue();
        }
        return targetSlot;
    }

    private static RuntimeException appendFailure(
            RuntimeException aggregate,
            State state,
            RuntimeException exception) {
        if (aggregate == null) {
            aggregate = new RpcException("solana RPC providers failed");
        }
        aggregate.addSuppressed(new RpcException(state.node.name() + " failed", exception));
        return aggregate;
    }

    private static RpcRateLimitException findRateLimit(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RpcRateLimitException limited) {
                return limited;
            }
        }
        return null;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}