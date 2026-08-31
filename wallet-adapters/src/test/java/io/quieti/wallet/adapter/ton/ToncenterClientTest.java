package io.quieti.wallet.adapter.ton;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToncenterClientTest {

    @Test
    void sendsApiKeyAndRetriesRateLimitUsingRetryAfter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> delays = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v3/masterchainInfo", exchange -> {
            assertEquals("secret", exchange.getRequestHeaders().getFirst("X-API-Key"));
            if (requests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "{\"last\":{\"seqno\":42,\"root_hash\":\"root42\"}}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            ToncenterClient client = new ToncenterClient(
                    new ObjectMapper(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"),
                    Duration.ofSeconds(1),
                    "secret",
                    1024,
                    java.net.http.HttpClient.newHttpClient(),
                    millis -> delays.add(Long.toString(millis)));

            assertEquals(42, client.masterchainInfo().path("last").path("seqno").longValue());
            assertEquals(2, requests.get());
            assertEquals(List.of("0"), delays);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildsToncenterBlockQuery() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/blocks", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"blocks\":[{\"seqno\":42,\"root_hash\":\"root42\"}]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToncenterClient client = new ToncenterClient(
                    new ObjectMapper(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), "", 1024);

            assertEquals("root42", client.block(-1, ToncenterClient.MASTERCHAIN_SHARD, 42)
                    .path("root_hash").asText());
            assertEquals("limit=1&seqno=42&shard=-9223372036854775808&workchain=-1", queries.get(0));
        } finally {
            server.stop(0);
        }
    }
}