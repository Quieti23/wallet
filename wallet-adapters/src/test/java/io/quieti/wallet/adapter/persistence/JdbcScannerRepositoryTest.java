package io.quieti.wallet.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.node.BitcoinNodeAdapter;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.scan.ChainScanAdapter;
import io.quieti.wallet.application.scan.CommitRequest;
import io.quieti.wallet.application.scan.CursorCheck;
import io.quieti.wallet.application.scan.FinalityEvaluation;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityRequest;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.OutboxEvent;
import io.quieti.wallet.application.scan.RollbackRequest;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCoordinator;
import io.quieti.wallet.application.scan.ScanCoordinatorConfig;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.StaleCursorException;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.domain.chain.Amount;
import io.quieti.wallet.domain.chain.AssetRef;
import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.PointRef;
import io.quieti.wallet.domain.deposit.DepositObservation;
import io.quieti.wallet.domain.deposit.ObservationId;
import io.quieti.wallet.domain.deposit.TransactionRef;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcScannerRepositoryTest {

    private static final ChainRef CHAIN = ChainRef.parse("solana:devnet");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.45")
            .withDatabaseName("go_wallet")
            .withUsername("wallet")
            .withPassword("wallet")
            .withInitScript("mysql/scan-schema.sql");

    private JdbcTemplate jdbc;
    private JdbcScannerRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcScannerRepository(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new ObjectMapper());
        jdbc.update("DELETE FROM deposit_state_history");
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM deposit_observations");
        jdbc.update("DELETE FROM assets");
        jdbc.update("DELETE FROM canonical_units");
        jdbc.update("DELETE FROM scan_cursors");
        jdbc.update("DELETE FROM watch_targets");
        jdbc.update("DELETE FROM chains");
        jdbc.update(
                "INSERT INTO chains(chain_ref, family, network, finality_policy) VALUES (?, ?, ?, JSON_OBJECT())",
                CHAIN.toString(), CHAIN.namespace(), CHAIN.reference());
    }

    @Test
    void loadsEmptyCursorAndVersionedWatchSnapshot() {
        ScanCursor empty = repository.loadCursor(CHAIN, "deposit");
        assertEquals(0, empty.version());
        assertNull(empty.point());

        jdbc.update("""
                INSERT INTO watch_targets
                    (chain_ref, normalized_target, owner_id, account_id, target_version)
                VALUES (?, 'target-a', 'owner-a', 'account-a', 3),
                       (?, 'target-b', 'owner-b', 'account-b', 7)
                """, CHAIN.toString(), CHAIN.toString());

        WatchSnapshot snapshot = repository.loadWatchSnapshot(CHAIN);
        assertEquals(7, snapshot.version());
        assertEquals(2, snapshot.targets().size());
    }

    @Test
    void readsGoCompatibleParentJsonFromCursorAndCanonicalHistory() {
        String parents = """
                [{"Scope":"shard:left","Position":"41","Hash":"left"},
                 {"Scope":"shard:right","Position":"41","Hash":"right"}]
                """;
        jdbc.update("""
                INSERT INTO scan_cursors
                    (chain_ref, scanner_id, position_scope, position, unit_hash, parents_json, version)
                VALUES (?, 'deposit', 'masterchain', '42', 'root', ?, 9)
                """, CHAIN.toString(), parents);
        jdbc.update("""
                INSERT INTO canonical_units
                    (chain_ref, position_scope, position, unit_hash, parents_json, canonical)
                VALUES (?, '', '42', 'root', ?, 1)
                """, CHAIN.toString(), parents);

        ScanCursor cursor = repository.loadCursor(CHAIN, "deposit");
        assertEquals(9, cursor.version());
        assertEquals(2, cursor.point().parents().size());
        assertEquals("shard:right", cursor.point().parents().get(1).scope());

        ChainPoint canonical = repository.canonicalPoint(CHAIN, "42").orElseThrow();
        assertTrue(canonical.parents().contains(cursor.point().parents().getFirst()));
    }

    @Test
    void commitsIdempotentObservationAndRejectsStaleCursor() {
        ChainPoint first = new ChainPoint(
                "",
                "1",
                "hash-1",
                List.of(new PointRef("", "0", "genesis")),
                null);
        DepositObservation observation = observation(first, "100");
        repository.commit(commit(0, 1, first, List.of(first), observation, "observed-1"));

        assertEquals(1, repository.loadCursor(CHAIN, "deposit").version());
        assertEquals(1, count("deposit_observations"));
        assertEquals(1, count("outbox_events"));
        assertEquals("genesis", jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(parents_json, '$[0].Hash'))
                FROM scan_cursors
                """, String.class));

        ChainPoint second = point("2", "hash-2");
        repository.commit(commit(1, 2, second, List.of(second), observation, "observed-2"));
        assertEquals(1, count("deposit_observations"));
        assertEquals(2, count("canonical_units"));

        assertThrows(
                StaleCursorException.class,
                () -> repository.commit(commit(1, 2, second, List.of(second), observation, "stale")));
        assertEquals(2, repository.loadCursor(CHAIN, "deposit").version());
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void rollsBackCursorUnitAndOutboxWhenObservationConflicts() {
        ChainPoint first = point("1", "hash-1");
        repository.commit(commit(0, 1, first, List.of(first), observation(first, "100"), "observed-1"));

        ChainPoint second = point("2", "hash-2");
        CommitRequest conflict = commit(
                1, 2, second, List.of(second), observation(second, "101"), "must-rollback");
        assertThrows(IllegalArgumentException.class, () -> repository.commit(conflict));

        assertEquals(1, repository.loadCursor(CHAIN, "deposit").version());
        assertEquals(1, count("canonical_units"));
        assertEquals(1, count("outbox_events"));
        assertEquals("100", jdbc.queryForObject(
                "SELECT amount_raw FROM deposit_observations", String.class));
    }

    @Test
    void rollsBackAllWritesWhenOutboxPayloadIsInvalid() {
        ChainPoint first = point("1", "hash-1");
        ScanBatch batch = new ScanBatch(
                List.of(new CanonicalUnit(CHAIN, first, true)),
                List.of(observation(first, "100")),
                new ScanCursor(CHAIN, "deposit", first, 1),
                true);
        OutboxEvent invalid = new OutboxEvent(
                "invalid-json",
                "deposit",
                "transaction-1",
                "deposit.observed",
                "not-json".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> repository.commit(
                new CommitRequest(CHAIN, "deposit", 0, batch, List.of(invalid))));

        assertEquals(0, repository.loadCursor(CHAIN, "deposit").version());
        assertEquals(0, count("canonical_units"));
        assertEquals(0, count("deposit_observations"));
        assertEquals(0, count("outbox_events"));
    }

    @Test
    void creditsAndReversesWithBalancedAppendOnlyLedger() {
        ChainPoint first = point("1", "hash-1");
        ChainPoint second = point("2", "hash-2");
        repository.commit(commit(
                0, 1, second, List.of(first, second), observation(second, "100"), "observed-1"));

        repository.advanceFinality(new FinalityRequest(
                CHAIN,
                "deposit",
                1,
                new FinalityEvaluation(second, second),
                List.of()));
        assertEquals("CREDITED", state());
        assertBalancedLedger(2, "credit", "debit");

        repository.rollback(new RollbackRequest(
                CHAIN, "deposit", 2, first, List.of(event("rollback-request"))));
        assertEquals("REVERSED", state());
        assertEquals(4, count("ledger_entries"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM canonical_units WHERE unit_hash='hash-2' AND canonical=0",
                Integer.class));
        assertEquals(3, repository.loadCursor(CHAIN, "deposit").version());
        assertEquals(0L, ledgerImbalance("deposit_credit"));
        assertEquals(0L, ledgerImbalance("deposit_reversal"));
    }

    @Test
    void restartReloadsCursorAndKeepsDuplicateObservationIdempotent() {
        ChainPoint first = point("1", "hash-1");
        DepositObservation observation = observation(first, "100");
        repository.commit(commit(0, 1, first, List.of(first), observation, "observed-1"));

        JdbcScannerRepository restarted = reopenRepository();
        ScanCursor restored = restarted.loadCursor(CHAIN, "deposit");
        assertEquals(1, restored.version());
        assertEquals(first, restored.point());

        ChainPoint second = point("2", "hash-2");
        restarted.commit(commit(1, 2, second, List.of(second), observation, "observed-2"));

        assertEquals(2, restarted.loadCursor(CHAIN, "deposit").version());
        assertEquals("hash-2", restarted.loadCursor(CHAIN, "deposit").point().hash());
        assertEquals(1, count("deposit_observations"));
        assertEquals(1, count("deposit_state_history"));
        assertEquals(2, count("canonical_units"));
    }

        @Test
        void matchesShadowObservationByPersistedSemantics() {
                ChainPoint first = point("1", "hash-1");
                repository.commit(commit(0, 1, first, List.of(first), observation(first, "100"), "observed-1"));

                assertTrue(repository.matchesObservation(observation(first, "100")));
                assertEquals(false, repository.matchesObservation(observation(first, "101")));
        }

    @Test
    void coordinatorRollsBackReloadsAndReincludesOnCompetingBranch() {
        ReorgAdapter adapter = new ReorgAdapter();
        ScanCoordinator coordinator = new ScanCoordinator(
                new ScanCoordinatorConfig("deposit", 8, 2),
                adapter,
                repository,
                SOLANA_POLICY);

        coordinator.cycle();
        assertEquals("old-2", repository.loadCursor(CHAIN, "deposit").point().hash());
        assertEquals(3, repository.loadCursor(CHAIN, "deposit").version());

        adapter.reorganized = true;
        coordinator.cycle();

        ScanCursor cursor = repository.loadCursor(CHAIN, "deposit");
        assertEquals("new-2", cursor.point().hash());
        assertEquals(6, cursor.version());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM canonical_units WHERE unit_hash='old-2' AND canonical=0",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM canonical_units WHERE unit_hash='new-2' AND canonical=1",
                Integer.class));
        assertEquals("CONFIRMING", state());
        assertEquals("new-2", jdbc.queryForObject(
                "SELECT inclusion_hash FROM deposit_observations", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM deposit_state_history WHERE reason='canonical_reinclusion'",
                Integer.class));
    }

    @Test
    void invalidBitcoinProviderResponseLeavesMysqlUnchanged() {
        String genesis = "%064x".formatted(1);
        String blockHash = "%064x".formatted(2);
        RpcTransport rpc = responses(
                "{\"chain\":\"signet\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false}",
                "\"" + blockHash + "\"",
                header(blockHash, genesis),
                "{\"chain\":\"signet\",\"blocks\":1,\"headers\":1,\"initialblockdownload\":false}",
                "\"" + blockHash + "\"",
                header(blockHash, genesis),
                "\"" + blockHash + "\"",
                "{\"hash\":\"" + blockHash + "\",\"height\":1,\"previousblockhash\":\""
                        + genesis + "\",\"time\":1,\"tx\":{}}");
        BitcoinNodeAdapter adapter = new BitcoinNodeAdapter(
                "BTC_SIGNET",
                new ChainCheckpoint(0, genesis),
                rpc,
                repository,
                1);
        FinalityPolicy policy = new FinalityPolicy() {
            @Override
            public FinalityTarget observationTarget(ChainRef chain) {
                return new FinalityTarget("tag", "latest");
            }

            @Override
            public FinalityTarget finalityTarget(ChainRef chain) {
                return new FinalityTarget("confirmations", "1");
            }
        };
        ScanCoordinator coordinator = new ScanCoordinator(
                new ScanCoordinatorConfig("deposit", 8, 0), adapter, repository, policy);

        assertThrows(IllegalArgumentException.class, coordinator::cycle);
        assertEquals(0, repository.loadCursor(adapter.chain(), "deposit").version());
        assertEquals(0, count("canonical_units"));
        assertEquals(0, count("deposit_observations"));
                assertEquals(0, count("deposit_state_history"));
                assertEquals(0, count("ledger_entries"));
        assertEquals(0, count("outbox_events"));
    }

    private CommitRequest commit(
            long expectedVersion,
            long nextVersion,
            ChainPoint cursorPoint,
            List<ChainPoint> units,
            DepositObservation observation,
            String eventId) {
        ScanBatch batch = new ScanBatch(
                units.stream().map(point -> new CanonicalUnit(CHAIN, point, true)).toList(),
                List.of(observation),
                new ScanCursor(CHAIN, "deposit", cursorPoint, nextVersion),
                true);
        return new CommitRequest(CHAIN, "deposit", expectedVersion, batch, List.of(event(eventId)));
    }

    private DepositObservation observation(ChainPoint inclusion, String amount) {
        return new DepositObservation(
                new ObservationId(
                        CHAIN,
                        new TransactionRef("signature", "transaction-1"),
                        "instruction:3.1"),
                new AssetRef(CHAIN, "native", "sol"),
                "source-address",
                "destination-address",
                new Amount(amount, 9),
                inclusion,
                Map.of("owner_id", "user-1", "account_id", "account-1"));
    }

    private OutboxEvent event(String id) {
        return new OutboxEvent(
                id,
                "deposit",
                "transaction-1",
                "deposit.observed",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

        private JdbcScannerRepository reopenRepository() {
                DataSource dataSource = new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                return new JdbcScannerRepository(
                                new JdbcTemplate(dataSource),
                                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                                new ObjectMapper());
        }

        private static RpcTransport responses(String... values) {
                Queue<JsonNode> responses = new ArrayDeque<>();
                ObjectMapper mapper = new ObjectMapper();
                for (String value : values) {
                        try {
                                responses.add(mapper.readTree(value));
                        } catch (java.io.IOException error) {
                                throw new IllegalArgumentException(error);
                        }
                }
                return (method, parameters) -> {
                        JsonNode response = responses.poll();
                        if (response == null) {
                                throw new AssertionError("Unexpected RPC call: " + method);
                        }
                        return response;
                };
        }

        private static String header(String hash, String parent) {
                return "{\"hash\":\"" + hash + "\",\"height\":1,\"previousblockhash\":\""
                                + parent + "\",\"time\":1,\"tx\":[]}";
        }

    private static ChainPoint point(String position, String hash) {
        return new ChainPoint("", position, hash, List.of(), null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM deposit_observations", String.class);
    }

    private void assertBalancedLedger(int count, String accountDirection, String clearingDirection) {
        assertEquals(count, count("ledger_entries"));
        assertEquals(accountDirection, jdbc.queryForObject(
                "SELECT direction FROM ledger_entries WHERE account_id='account-1'", String.class));
        assertEquals(clearingDirection, jdbc.queryForObject(
                "SELECT direction FROM ledger_entries WHERE account_id='clearing:deposits'", String.class));
    }

        private long ledgerImbalance(String entryType) {
        return jdbc.queryForObject("""
                SELECT SUM(CASE WHEN direction='credit' THEN CAST(amount_raw AS SIGNED)
                                ELSE -CAST(amount_raw AS SIGNED) END)
                                FROM ledger_entries WHERE entry_type = ?
                                """, Long.class, entryType);
    }

        private static final FinalityPolicy SOLANA_POLICY = new FinalityPolicy() {
                @Override
                public FinalityTarget observationTarget(ChainRef chain) {
                        return new FinalityTarget("commitment", "confirmed");
                }

                @Override
                public FinalityTarget finalityTarget(ChainRef chain) {
                        return new FinalityTarget("commitment", "finalized");
                }
        };

        private final class ReorgAdapter implements ChainScanAdapter {
                private final ChainPoint first = new ChainPoint(
                                "", "1", "hash-1", List.of(new PointRef("", "0", "genesis")), null);
                private final ChainPoint oldSecond = new ChainPoint(
                                "", "2", "old-2", List.of(new PointRef("", "1", "hash-1")), null);
                private final ChainPoint newSecond = new ChainPoint(
                                "", "2", "new-2", List.of(new PointRef("", "1", "hash-1")), null);
                private boolean reorganized;
                private boolean reorgReported;

                @Override
                public ChainRef chain() {
                        return CHAIN;
                }

                @Override
                public ChainPoint observationHead(FinalityPolicy policy) {
                        return reorganized ? newSecond : oldSecond;
                }

                @Override
                public CursorCheck verifyCursor(ScanCursor cursor) {
                        if (reorganized && !reorgReported && cursor.point() != null
                                        && cursor.point().hash().equals(oldSecond.hash())) {
                                reorgReported = true;
                                return new CursorCheck(false, "competing branch");
                        }
                        return new CursorCheck(true, "");
                }

                @Override
                public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
                        ChainPoint next;
                        List<DepositObservation> observations;
                        if (cursor.point() == null) {
                                next = first;
                                observations = List.of();
                        } else if (cursor.point().hash().equals(first.hash())) {
                                next = reorganized ? newSecond : oldSecond;
                                observations = List.of(observation(next, "100"));
                        } else {
                                return new ScanBatch(List.of(), List.of(), null, false);
                        }
                        return new ScanBatch(
                                        List.of(new CanonicalUnit(CHAIN, next, true)),
                                        observations,
                                        new ScanCursor(CHAIN, cursor.scannerId(), next, cursor.version() + 1),
                                        true);
                }

                @Override
                public ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth) {
                        return first;
                }

                @Override
                public ChainPoint finalityHead(FinalityPolicy policy) {
                        return new ChainPoint("", "0", "genesis", List.of(), null);
                }
        }
}