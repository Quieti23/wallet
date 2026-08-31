package io.quieti.wallet.application.scan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanContractTest {

    private static final ChainRef CHAIN = ChainRef.parse("solana:devnet");

    @Test
    void acceptsAProgressingGoCompatibleBatch() {
        ChainPoint currentPoint = point("100", "hash-100");
        ChainPoint nextPoint = point("102", "hash-102");
        ScanCursor current = new ScanCursor(CHAIN, "deposit", currentPoint, 7);
        ScanBatch batch = new ScanBatch(
                List.of(new CanonicalUnit(CHAIN, nextPoint, true)),
                List.of(),
                new ScanCursor(CHAIN, "deposit", nextPoint, 8),
                true);

        assertDoesNotThrow(() -> batch.validateAgainst(current));
    }

    @Test
    void rejectsProgressWithoutCursorVersionAdvance() {
        ChainPoint currentPoint = point("100", "hash-100");
        ScanCursor current = new ScanCursor(CHAIN, "deposit", currentPoint, 7);
        ScanBatch batch = new ScanBatch(
                List.of(),
                List.of(),
                new ScanCursor(CHAIN, "deposit", point("102", "hash-102"), 7),
                true);

        assertThrows(IllegalArgumentException.class, () -> batch.validateAgainst(current));
    }

    @Test
    void rejectsDataInANoProgressBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanBatch(
                        List.of(new CanonicalUnit(CHAIN, point("100", "hash-100"), true)),
                        List.of(),
                        null,
                        false));
    }

    private static ChainPoint point(String position, String hash) {
        return new ChainPoint("", position, hash, List.of(), null);
    }
}