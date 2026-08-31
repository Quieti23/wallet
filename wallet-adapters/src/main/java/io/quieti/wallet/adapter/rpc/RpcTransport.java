package io.quieti.wallet.adapter.rpc;

import com.fasterxml.jackson.databind.JsonNode;

public interface RpcTransport {

    JsonNode call(String method, Object... parameters);
}