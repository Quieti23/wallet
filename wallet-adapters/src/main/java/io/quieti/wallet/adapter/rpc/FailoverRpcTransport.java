package io.quieti.wallet.adapter.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FailoverRpcTransport implements RpcTransport {

    private final List<RpcTransport> transports;

    public FailoverRpcTransport(List<RpcTransport> transports) {
        this.transports = List.copyOf(Objects.requireNonNull(transports, "transports"));
        if (this.transports.isEmpty() || this.transports.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("at least one RPC transport is required");
        }
    }

    @Override
    public JsonNode call(String method, Object... parameters) {
        List<RuntimeException> failures = new ArrayList<>();
        for (int index = 0; index < transports.size(); index++) {
            try {
                return transports.get(index).call(method, parameters);
            } catch (RuntimeException exception) {
                failures.add(new RpcException("RPC[" + index + "] failed for " + method, exception));
            }
        }
        RpcException failure = new RpcException("all RPC providers failed for " + method);
        failures.forEach(failure::addSuppressed);
        throw failure;
    }
}