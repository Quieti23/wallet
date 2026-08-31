package io.quieti.wallet.application.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quieti.wallet.domain.chain.ChainRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScannerSupervisorTest {

    private static final ChainRef SOLANA = ChainRef.parse("solana:devnet");
    private static final ChainRef EVM = ChainRef.parse("eip155:1");

    @Test
    void rejectsDuplicateScannerIdentity() {
        ScannerSupervisor supervisor = new ScannerSupervisor(null);
        supervisor.add(scanner(SOLANA, "deposit", () -> {}, () -> {}));

        assertThrows(IllegalArgumentException.class,
                () -> supervisor.add(scanner(SOLANA, "deposit", () -> {}, () -> {})));
        supervisor.close();
    }

    @Test
    void isolatesCycleFailuresAndClosesRuntimesInReverseOrder() throws Exception {
        CountDownLatch healthyCycles = new CountDownLatch(2);
        CountDownLatch reportedFailure = new CountDownLatch(1);
        List<String> closed = new ArrayList<>();
        ScannerSupervisor supervisor = new ScannerSupervisor(
                (chain, scannerId, failure) -> reportedFailure.countDown());
        supervisor.add(scanner(
                SOLANA,
                "deposit",
                () -> {
                    throw new IllegalStateException("provider unavailable");
                },
                () -> closed.add("solana")));
        supervisor.add(scanner(
                EVM,
                "deposit",
                healthyCycles::countDown,
                () -> closed.add("evm")));

        supervisor.start();
        assertTrue(reportedFailure.await(2, TimeUnit.SECONDS));
        assertTrue(healthyCycles.await(2, TimeUnit.SECONDS));
        supervisor.close();

        assertEquals(List.of("evm", "solana"), closed);
        assertThrows(IllegalStateException.class, supervisor::start);
    }

    private static SupervisedScanner scanner(
            ChainRef chain,
            String scannerId,
            ScanCycleRunner runner,
            AutoCloseable runtime) {
        return new SupervisedScanner(
                chain, scannerId, Duration.ofMillis(10), runner, runtime);
    }
}