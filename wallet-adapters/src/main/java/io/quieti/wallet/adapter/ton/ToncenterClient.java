package io.quieti.wallet.adapter.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.adapter.rpc.RpcException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToncenterClient implements ToncenterApi {

    public static final String MASTERCHAIN_SHARD = "-9223372036854775808";
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration ANONYMOUS_STEP = Duration.ofMillis(1100);

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final String apiKey;
    private final int maxResponseBytes;
    private final Sleeper sleeper;
    private long nextAnonymousRequestNanos;

    public ToncenterClient(
            ObjectMapper mapper,
            URI endpoint,
            Duration connectTimeout,
            Duration requestTimeout,
            String apiKey,
            int maxResponseBytes) {
        this(
                mapper,
                endpoint,
                requestTimeout,
                apiKey,
                maxResponseBytes,
                HttpClient.newBuilder().connectTimeout(positive(connectTimeout, "connectTimeout")).build(),
                Thread::sleep);
    }

    ToncenterClient(
            ObjectMapper mapper,
            URI endpoint,
            Duration requestTimeout,
            String apiKey,
            int maxResponseBytes,
            HttpClient client,
            Sleeper sleeper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = requireEndpoint(endpoint);
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.apiKey = Objects.requireNonNullElse(apiKey, "");
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.client = Objects.requireNonNull(client, "client");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public JsonNode masterchainInfo() {
        return get("masterchainInfo", Map.of());
    }

    public JsonNode block(long workchain, String shard, long seqno) {
        JsonNode blocks = get("blocks", Map.of(
                "workchain", Long.toString(workchain),
                "shard", shard,
                "seqno", Long.toString(seqno),
                "limit", "1")).path("blocks");
        if (!blocks.isArray() || blocks.size() != 1 || blocks.get(0).path("root_hash").asText().isBlank()) {
            throw new RpcException("TON blocks response must contain exactly one complete block");
        }
        return blocks.get(0);
    }

    public List<JsonNode> transactions(long masterchainSeqno) {
        return pages("transactions", "transactions", Map.of(
                "mc_seqno", Long.toString(masterchainSeqno),
                "sort", "asc"));
    }

    public List<JsonNode> jettonTransfers(long blockTime) {
        return pages("jetton/transfers", "jetton_transfers", Map.of(
                "start_utime", Long.toString(blockTime),
                "end_utime", Long.toString(Math.addExact(blockTime, 1)),
                "sort", "asc"));
    }

    private List<JsonNode> pages(String path, String field, Map<String, String> parameters) {
        List<JsonNode> values = new ArrayList<>();
        for (int offset = 0; ; offset = Math.addExact(offset, PAGE_SIZE)) {
            Map<String, String> query = new java.util.HashMap<>(parameters);
            query.put("limit", Integer.toString(PAGE_SIZE));
            query.put("offset", Integer.toString(offset));
            JsonNode page = get(path, query).path(field);
            if (!page.isArray()) {
                throw new RpcException("TON " + path + " response field " + field + " must be an array");
            }
            page.forEach(values::add);
            if (page.size() < PAGE_SIZE) {
                return List.copyOf(values);
            }
        }
    }

    private JsonNode get(String path, Map<String, String> parameters) {
        URI uri = requestUri(path, parameters);
        for (int attempt = 0; ; attempt++) {
            try {
                paceAnonymousRequests();
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .GET();
                if (!apiKey.isBlank()) {
                    request.header("X-API-Key", apiKey);
                }
                HttpResponse<byte[]> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    sleep(retryAfter(response));
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RpcException("TON HTTP status " + response.statusCode() + " for /" + path);
                }
                if (response.body().length > maxResponseBytes) {
                    throw new RpcException("TON response exceeds configured limit for /" + path);
                }
                return mapper.readTree(response.body());
            } catch (IOException error) {
                throw new RpcException("TON I/O failure for /" + path, error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RpcException("TON request interrupted for /" + path, error);
            }
        }
    }

    private synchronized void paceAnonymousRequests() throws InterruptedException {
        if (!apiKey.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        if (nextAnonymousRequestNanos > now) {
            sleeper.sleep(Duration.ofNanos(nextAnonymousRequestNanos - now).toMillis());
        }
        nextAnonymousRequestNanos = System.nanoTime() + ANONYMOUS_STEP.toNanos();
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? ANONYMOUS_STEP : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return ANONYMOUS_STEP;
        }
    }

    private void sleep(Duration delay) throws InterruptedException {
        sleeper.sleep(delay.toMillis());
    }

    private URI requestUri(String path, Map<String, String> parameters) {
        StringBuilder value = new StringBuilder(endpoint.toString());
        if (!value.toString().endsWith("/")) {
            value.append('/');
        }
        value.append(path);
        if (!parameters.isEmpty()) {
            value.append('?');
            parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> value
                    .append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()))
                    .append('&'));
            value.setLength(value.length() - 1);
        }
        return URI.create(value.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
            throw new IllegalArgumentException("TON endpoint must use HTTP or HTTPS");
        }
        return endpoint;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}