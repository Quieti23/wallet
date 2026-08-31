package io.quieti.wallet.adapter.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SolanaRpcPoolTest {

    @Test
    void coolsDownRateLimitedNodeAndRetriesAfterDeadline() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        AtomicInteger limitedCalls = new AtomicInteger();
        AtomicBoolean limited = new AtomicBoolean(true);
        RpcTransport limitedNode = (method, parameters) -> {
            limitedCalls.incrementAndGet();
            if (limited.get()) {
                throw new RpcRateLimitException("limited", Duration.ofSeconds(5));
            }
            return JsonNodeFactory.instance.numberNode(51);
        };
        AtomicInteger healthyCalls = new AtomicInteger();
        RpcTransport healthyNode = (method, parameters) -> {
            healthyCalls.incrementAndGet();
            return JsonNodeFactory.instance.numberNode(50);
        };
        SolanaRpcPool pool = new SolanaRpcPool(
                List.of(
                        new SolanaRpcPool.Node("limited", limitedNode),
                        new SolanaRpcPool.Node("healthy", healthyNode)),
                5,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                clock);

        assertEquals(50, pool.call("getSlot").longValue());
        assertEquals(50, pool.call("getSlot").longValue());
        assertEquals(1, limitedCalls.get());
        assertEquals(2, healthyCalls.get());

        clock.advance(Duration.ofSeconds(5));
        limited.set(false);
        assertEquals(51, pool.call("getSlot").longValue());
        assertEquals(2, limitedCalls.get());
    }

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        assertEquals(Duration.ofSeconds(7), HttpJsonRpcTransport.parseRetryAfter("7", now));
        String date = ZonedDateTime.ofInstant(now.plusSeconds(9), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(Duration.ofSeconds(9), HttpJsonRpcTransport.parseRetryAfter(date, now));
        assertEquals(Duration.ZERO, HttpJsonRpcTransport.parseRetryAfter("invalid", now));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
