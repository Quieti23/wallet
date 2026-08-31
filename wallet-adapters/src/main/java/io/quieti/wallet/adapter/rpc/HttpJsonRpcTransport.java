package io.quieti.wallet.adapter.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpJsonRpcTransport implements RpcTransport {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final String authorization;
    private final String apiKey;
    private final int maxResponseBytes;
    private final AtomicLong requestIds = new AtomicLong();

    public HttpJsonRpcTransport(
            ObjectMapper mapper,
            URI endpoint,
            Duration connectTimeout,
            Duration requestTimeout,
            String username,
            String password,
            String apiKey,
            int maxResponseBytes) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
                .build();
        this.authorization = basicAuthorization(username, password);
        this.apiKey = apiKey;
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public JsonNode call(String method, Object... parameters) {
        long requestId = requestIds.incrementAndGet();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", requestId);
        payload.put("method", method);
        ArrayNode params = payload.putArray("params");
        for (Object parameter : parameters) {
            params.add(mapper.valueToTree(parameter));
        }

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("X-API-Key", apiKey);
            }
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 429) {
                Duration retryAfter = parseRetryAfter(
                        response.headers().firstValue("Retry-After").orElse(""), Instant.now());
                throw new RpcRateLimitException("RPC HTTP status 429 for " + method, retryAfter);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RpcException("RPC HTTP status " + response.statusCode() + " for " + method);
            }
            if (response.body().length > maxResponseBytes) {
                throw new RpcException("RPC response exceeds configured limit for " + method);
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.path("id").asLong(-1) != requestId) {
                throw new RpcException("RPC response id mismatch for " + method);
            }
            if (root.hasNonNull("error")) {
                throw new RpcException("RPC error for " + method + ": " + root.get("error"));
            }
            JsonNode result = root.get("result");
            if (result == null) {
                throw new RpcException("RPC response has no result for " + method);
            }
            return result;
        } catch (IOException error) {
            throw new RpcException("RPC I/O failure for " + method, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RpcException("RPC interrupted for " + method, error);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String basicAuthorization(String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String credentials = username + ":" + Objects.requireNonNull(password, "password");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    static Duration parseRetryAfter(String value, Instant now) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(normalized);
            return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return retryAt.isAfter(now) ? Duration.between(now, retryAt) : Duration.ZERO;
            } catch (DateTimeParseException invalidDate) {
                return Duration.ZERO;
            }
        }
    }
}