package io.quieti.wallet.adapter.rpc;

import java.time.Duration;
import java.util.Objects;

public final class RpcRateLimitException extends RpcException {

    private final Duration retryAfter;

    public RpcRateLimitException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}