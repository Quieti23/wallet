package io.quieti.wallet.adapter.persistence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quieti.wallet.application.port.CanonicalReader;
import io.quieti.wallet.application.port.ScannerRepository;
import io.quieti.wallet.application.scan.CommitRequest;
import io.quieti.wallet.application.scan.FinalityRequest;
import io.quieti.wallet.application.scan.OutboxEvent;
import io.quieti.wallet.application.scan.RollbackRequest;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.ShadowReferenceReader;
import io.quieti.wallet.application.scan.ShadowStateReader;
import io.quieti.wallet.application.scan.StaleCursorException;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import io.quieti.wallet.domain.chain.PointRef;
import io.quieti.wallet.domain.deposit.DepositObservation;
import io.quieti.wallet.domain.deposit.DepositState;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcScannerRepository
    implements ScannerRepository, CanonicalReader, ShadowReferenceReader, ShadowStateReader {

    private static final TypeReference<List<PersistedPointRef>> PARENT_LIST = new TypeReference<>() {};
    private static final String METADATA_OWNER_ID = "owner_id";
    private static final String METADATA_ACCOUNT_ID = "account_id";
    private static final String DEPOSIT_CLEARING_ACCOUNT = "clearing:deposits";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public JdbcScannerRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public ScanCursor loadCursor(ChainRef chain, String scannerId) {
        Objects.requireNonNull(chain, "chain");
        requireText(scannerId, "scannerId");
        List<ScanCursor> values = jdbc.query("""
                        SELECT position_scope, position, unit_hash, parents_json, unit_time, version
                        FROM scan_cursors
                        WHERE chain_ref = ? AND scanner_id = ?
                        """,
                (resultSet, rowNumber) -> mapCursor(resultSet, chain, scannerId),
                chain.toString(),
                scannerId);
        return values.isEmpty() ? new ScanCursor(chain, scannerId, null, 0) : values.getFirst();
    }

    @Override
    public WatchSnapshot loadWatchSnapshot(ChainRef chain) {
        Objects.requireNonNull(chain, "chain");
        List<VersionedWatchTarget> values = jdbc.query("""
                        SELECT normalized_target, owner_id, account_id, target_version
                        FROM watch_targets
                        WHERE chain_ref = ? AND active = 1 AND effective_at <= CURRENT_TIMESTAMP(6)
                        ORDER BY normalized_target
                        """,
                (resultSet, rowNumber) -> new VersionedWatchTarget(
                        new WatchTarget(
                                resultSet.getString("normalized_target"),
                                resultSet.getString("owner_id"),
                                resultSet.getString("account_id")),
                        resultSet.getLong("target_version")),
                chain.toString());
        long version = values.stream().mapToLong(VersionedWatchTarget::version).max().orElse(0);
        return new WatchSnapshot(version, values.stream().map(VersionedWatchTarget::target).toList());
    }

    @Override
    public Optional<ChainPoint> canonicalPoint(ChainRef chain, String position) {
        Objects.requireNonNull(chain, "chain");
        requireText(position, "position");
        List<ChainPoint> values = jdbc.query("""
                        SELECT position_scope, position, unit_hash, parents_json, unit_time
                        FROM canonical_units
                        WHERE chain_ref = ? AND position_scope = '' AND position = ? AND canonical = 1
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                (resultSet, rowNumber) -> mapPoint(resultSet),
                chain.toString(),
                position);
        return values.stream().findFirst();
    }

        @Override
        public boolean matchesObservation(DepositObservation observation) {
                Objects.requireNonNull(observation, "observation");
                return Boolean.TRUE.equals(jdbc.queryForObject("""
                                                SELECT COUNT(*) = 1
                                                FROM deposit_observations
                                                WHERE chain_ref = ?
                                                    AND transaction_kind = ?
                                                    AND transaction_ref = ?
                                                    AND source = ?
                                                    AND asset_ref = ?
                                                    AND from_target = ?
                                                    AND to_target = ?
                                                    AND amount_raw = ?
                                                    AND amount_decimals = ?
                                                    AND inclusion_scope = ?
                                                    AND inclusion_position = ?
                                                    AND inclusion_hash = ?
                                                    AND owner_id = ?
                                                    AND account_id = ?
                                                """,
                                Boolean.class,
                                observation.id().chain().toString(),
                                observation.id().transaction().kind(),
                                observation.id().transaction().value(),
                                observation.id().source(),
                                observation.asset().toString(),
                                observation.from(),
                                observation.to(),
                                observation.amount().raw(),
                                observation.amount().decimals(),
                                observation.inclusion().scope(),
                                observation.inclusion().position(),
                                observation.inclusion().hash(),
                                observation.metadata().getOrDefault(METADATA_OWNER_ID, ""),
                                observation.metadata().getOrDefault(METADATA_ACCOUNT_ID, "")));
        }

    @Override
    public void commit(CommitRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.batch().progress()) {
            throw new IllegalArgumentException("commit requires a progressing batch");
        }
        transactions.executeWithoutResult(status -> {
            ensureChain(request.chain());
            advanceCursor(request);
            request.batch().units().forEach(this::upsertCanonicalUnit);
            request.batch().observations().forEach(this::upsertObservation);
            insertOutbox(request.outbox());
        });
    }

    @Override
    public void rollback(RollbackRequest request) {
        Objects.requireNonNull(request, "request");
        transactions.executeWithoutResult(status -> {
            long ancestorId = canonicalUnitId(request.chain(), request.ancestor());
            assertCursorVersion(
                    request.chain(), request.scannerId(), request.expectedVersion(), "rollback");
            jdbc.update("""
                    UPDATE canonical_units
                    SET canonical = 0, reorged_at = UTC_TIMESTAMP(6)
                    WHERE chain_ref = ? AND position_scope = ? AND canonical = 1 AND id > ?
                    """, request.chain().toString(), request.ancestor().scope(), ancestorId);
            reorgObservations(request.chain(), request.ancestor().scope(), ancestorId);
            writeCursor(
                    request.chain(),
                    request.scannerId(),
                    request.ancestor(),
                    request.expectedVersion() + 1);
            insertOutbox(request.outbox());
        });
    }

    @Override
    public void advanceFinality(FinalityRequest request) {
        Objects.requireNonNull(request, "request");
        transactions.executeWithoutResult(status -> {
            assertCursorVersion(
                    request.chain(), request.scannerId(), request.expectedVersion(), "advance finality");
            long observationId = optionalCanonicalUnitId(
                    request.chain(), request.evaluation().observationHead());
            long finalityId = optionalCanonicalUnitId(
                    request.chain(), request.evaluation().finalityHead());
            transitionByBoundary(
                    request.chain(),
                    request.evaluation().observationHead().scope(),
                    observationId,
                    DepositState.OBSERVED,
                    DepositState.CONFIRMING,
                    "observation_head");
            transitionByBoundary(
                    request.chain(),
                    request.evaluation().finalityHead().scope(),
                    finalityId,
                    DepositState.CONFIRMING,
                    DepositState.FINALITY_READY,
                    "finality_head");
            if (finalityId != 0) {
                selectLifecycleDeposits(
                                request.chain(),
                                request.evaluation().finalityHead().scope(),
                                finalityId,
                                DepositState.FINALITY_READY,
                                false,
                                true)
                        .forEach(value -> applyLifecycle(value, false));
            }
            bumpCursorVersion(request.chain(), request.scannerId(), request.expectedVersion());
            insertOutbox(request.outbox());
        });
    }

    private void ensureChain(ChainRef chain) {
        jdbc.update("""
                INSERT IGNORE INTO chains(chain_ref, family, network, finality_policy, enabled)
                VALUES (?, ?, ?, JSON_OBJECT(), 1)
                """, chain.toString(), chain.namespace(), chain.reference());
    }

    private void advanceCursor(CommitRequest request) {
        ScanCursor next = request.batch().nextCursor();
        PointValues point = pointValues(next.point());
        int rows = jdbc.update("""
                UPDATE scan_cursors
                SET position_scope = ?, position = ?, unit_hash = ?, parents_json = ?, unit_time = ?,
                    version = ?, status = 'running', error_message = NULL
                WHERE chain_ref = ? AND scanner_id = ? AND version = ?
                """,
                point.scope(), point.position(), point.hash(), point.parentsJson(), point.time(),
                next.version(), request.chain().toString(), request.scannerId(), request.expectedVersion());
        if (rows == 1) {
            return;
        }
        if (request.expectedVersion() != 0) {
            throw new StaleCursorException("commit");
        }
        try {
            jdbc.update("""
                    INSERT INTO scan_cursors
                        (chain_ref, scanner_id, position_scope, position, unit_hash,
                         parents_json, unit_time, version, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running')
                    """,
                    request.chain().toString(), request.scannerId(), point.scope(), point.position(),
                    point.hash(), point.parentsJson(), point.time(), next.version());
        } catch (DuplicateKeyException exception) {
            throw new StaleCursorException("commit");
        }
    }

    private void upsertCanonicalUnit(CanonicalUnit unit) {
        PointValues point = pointValues(unit.point());
        jdbc.update("""
                INSERT INTO canonical_units
                    (chain_ref, position_scope, position, unit_hash, parents_json, unit_time, canonical)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    parents_json = VALUES(parents_json), unit_time = VALUES(unit_time),
                    canonical = VALUES(canonical), reorged_at = NULL
                """,
                unit.chain().toString(), point.scope(), point.position(), point.hash(),
                point.parentsJson(), point.time(), unit.canonical());
    }

    private void upsertObservation(DepositObservation observation) {
        String ownerId = requireMetadata(observation, METADATA_OWNER_ID);
        String accountId = requireMetadata(observation, METADATA_ACCOUNT_ID);
        String metadata = writeJson(observation.metadata(), "observation metadata");
        jdbc.update("""
                INSERT INTO assets(asset_ref, chain_ref, standard, locator, decimals)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE decimals = VALUES(decimals)
                """,
                observation.asset().toString(), observation.id().chain().toString(),
                observation.asset().standard(), observation.asset().locator(), observation.amount().decimals());
        int inserted = jdbc.update("""
                INSERT IGNORE INTO deposit_observations
                    (chain_ref, transaction_kind, transaction_ref, source, asset_ref,
                     from_target, to_target, amount_raw, amount_decimals, inclusion_scope,
                     inclusion_position, inclusion_hash, owner_id, account_id, metadata, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OBSERVED')
                """,
                observation.id().chain().toString(), observation.id().transaction().kind(),
                observation.id().transaction().value(), observation.id().source(),
                observation.asset().toString(), observation.from(), observation.to(),
                observation.amount().raw(), observation.amount().decimals(),
                observation.inclusion().scope(), observation.inclusion().position(),
                observation.inclusion().hash(), ownerId, accountId, metadata);
        if (inserted == 1) {
            jdbc.update("""
                    INSERT INTO deposit_state_history(observation_id, from_state, to_state, reason)
                    SELECT id, NULL, 'OBSERVED', 'scan_observation'
                    FROM deposit_observations
                    WHERE chain_ref = ? AND transaction_kind = ? AND transaction_ref = ? AND source = ?
                    """,
                    observation.id().chain().toString(), observation.id().transaction().kind(),
                    observation.id().transaction().value(), observation.id().source());
            return;
        }
        PersistedObservation existing = jdbc.queryForObject("""
                        SELECT asset_ref, amount_raw, to_target, owner_id, account_id, state
                        FROM deposit_observations
                        WHERE chain_ref = ? AND transaction_kind = ? AND transaction_ref = ? AND source = ?
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> new PersistedObservation(
                        resultSet.getString("asset_ref"), resultSet.getString("amount_raw"),
                        resultSet.getString("to_target"), resultSet.getString("owner_id"),
                        resultSet.getString("account_id"), DepositState.valueOf(resultSet.getString("state"))),
                observation.id().chain().toString(), observation.id().transaction().kind(),
                observation.id().transaction().value(), observation.id().source());
        if (existing == null
                || !existing.assetRef().equals(observation.asset().toString())
                || !existing.amountRaw().equals(observation.amount().raw())
                || !existing.toTarget().equals(observation.to())
                || !existing.ownerId().equals(ownerId)
                || !existing.accountId().equals(accountId)) {
            throw new IllegalArgumentException(
                    "observation identity conflicts with persisted immutable facts: " + observation.id());
        }
        if (existing.state() != DepositState.REORGED) {
            return;
        }
        jdbc.update("""
                INSERT INTO deposit_state_history(observation_id, from_state, to_state, reason)
                SELECT id, 'REORGED', 'CONFIRMING', 'canonical_reinclusion'
                FROM deposit_observations
                WHERE chain_ref = ? AND transaction_kind = ? AND transaction_ref = ? AND source = ?
                """,
                observation.id().chain().toString(), observation.id().transaction().kind(),
                observation.id().transaction().value(), observation.id().source());
        jdbc.update("""
                UPDATE deposit_observations
                SET inclusion_scope = ?, inclusion_position = ?, inclusion_hash = ?,
                    state = 'CONFIRMING', reorged_at = NULL
                WHERE chain_ref = ? AND transaction_kind = ? AND transaction_ref = ?
                  AND source = ? AND state = 'REORGED'
                """,
                observation.inclusion().scope(), observation.inclusion().position(),
                observation.inclusion().hash(), observation.id().chain().toString(),
                observation.id().transaction().kind(), observation.id().transaction().value(),
                observation.id().source());
    }

    private long canonicalUnitId(ChainRef chain, ChainPoint point) {
        List<Long> values = jdbc.query("""
                        SELECT id FROM canonical_units
                        WHERE chain_ref = ? AND position_scope = ? AND position = ?
                          AND unit_hash = ? AND canonical = 1
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                chain.toString(), point.scope(), point.position(), point.hash());
        if (values.size() != 1) {
            throw new IllegalArgumentException("canonical ancestor was not found");
        }
        return values.getFirst();
    }

    private long optionalCanonicalUnitId(ChainRef chain, ChainPoint point) {
        List<Long> values = jdbc.query("""
                        SELECT id FROM canonical_units
                        WHERE chain_ref = ? AND position_scope = ? AND position = ?
                          AND unit_hash = ? AND canonical = 1
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                chain.toString(), point.scope(), point.position(), point.hash());
        return values.isEmpty() ? 0 : values.getFirst();
    }

    private void assertCursorVersion(
            ChainRef chain, String scannerId, long expectedVersion, String operation) {
        List<Long> versions = jdbc.query("""
                        SELECT version FROM scan_cursors
                        WHERE chain_ref = ? AND scanner_id = ?
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                chain.toString(), scannerId);
        if (versions.size() != 1 || versions.getFirst() != expectedVersion) {
            throw new StaleCursorException(operation);
        }
    }

    private void writeCursor(ChainRef chain, String scannerId, ChainPoint point, long version) {
        PointValues values = pointValues(point);
        jdbc.update("""
                UPDATE scan_cursors
                SET position_scope = ?, position = ?, unit_hash = ?, parents_json = ?,
                    unit_time = ?, version = ?, status = 'running', error_message = NULL
                WHERE chain_ref = ? AND scanner_id = ?
                """,
                values.scope(), values.position(), values.hash(), values.parentsJson(), values.time(),
                version, chain.toString(), scannerId);
    }

    private void bumpCursorVersion(ChainRef chain, String scannerId, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE scan_cursors SET version = version + 1
                WHERE chain_ref = ? AND scanner_id = ? AND version = ?
                """, chain.toString(), scannerId, expectedVersion);
        if (rows != 1) {
            throw new StaleCursorException("advance finality");
        }
    }

    private void transitionByBoundary(
            ChainRef chain,
            String scope,
            long boundaryId,
            DepositState from,
            DepositState to,
            String reason) {
        if (boundaryId == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO deposit_state_history(observation_id, from_state, to_state, reason)
                SELECT d.id, ?, ?, ?
                FROM deposit_observations d
                JOIN canonical_units u ON u.chain_ref = d.chain_ref
                 AND u.position_scope = d.inclusion_scope
                 AND u.position = d.inclusion_position AND u.unit_hash = d.inclusion_hash
                WHERE d.chain_ref = ? AND u.position_scope = ? AND d.state = ?
                  AND u.canonical = 1 AND u.id <= ?
                """, from.name(), to.name(), reason, chain.toString(), scope, from.name(), boundaryId);
        jdbc.update("""
                UPDATE deposit_observations d
                JOIN canonical_units u ON u.chain_ref = d.chain_ref
                 AND u.position_scope = d.inclusion_scope
                 AND u.position = d.inclusion_position AND u.unit_hash = d.inclusion_hash
                SET d.state = ?, d.finality_updated_at = UTC_TIMESTAMP(6)
                WHERE d.chain_ref = ? AND u.position_scope = ? AND d.state = ?
                  AND u.canonical = 1 AND u.id <= ?
                """, to.name(), chain.toString(), scope, from.name(), boundaryId);
    }

    private void reorgObservations(ChainRef chain, String scope, long ancestorId) {
        selectLifecycleDeposits(chain, scope, ancestorId, DepositState.CREDITED, true, false)
                .forEach(value -> applyLifecycle(value, true));
        jdbc.update("""
                INSERT INTO deposit_state_history(observation_id, from_state, to_state, reason)
                SELECT d.id, d.state, 'REORGED', 'canonical_reorg'
                FROM deposit_observations d
                JOIN canonical_units u ON u.chain_ref = d.chain_ref
                 AND u.position_scope = d.inclusion_scope
                 AND u.position = d.inclusion_position AND u.unit_hash = d.inclusion_hash
                WHERE d.chain_ref = ? AND u.position_scope = ?
                  AND d.state IN ('OBSERVED','CONFIRMING','FINALITY_READY') AND u.id > ?
                """, chain.toString(), scope, ancestorId);
        jdbc.update("""
                UPDATE deposit_observations d
                JOIN canonical_units u ON u.chain_ref = d.chain_ref
                 AND u.position_scope = d.inclusion_scope
                 AND u.position = d.inclusion_position AND u.unit_hash = d.inclusion_hash
                SET d.state = 'REORGED', d.reorged_at = UTC_TIMESTAMP(6)
                WHERE d.chain_ref = ? AND u.position_scope = ?
                  AND d.state IN ('OBSERVED','CONFIRMING','FINALITY_READY') AND u.id > ?
                """, chain.toString(), scope, ancestorId);
    }

    private List<LifecycleDeposit> selectLifecycleDeposits(
            ChainRef chain,
            String scope,
            long boundaryId,
            DepositState state,
            boolean afterBoundary,
            boolean canonicalOnly) {
        String comparison = afterBoundary ? ">" : "<=";
        String canonicalCondition = canonicalOnly ? "AND u.canonical = 1" : "";
        return jdbc.query("""
                        SELECT d.id, d.account_id, d.asset_ref, d.amount_raw, d.state
                        FROM deposit_observations d
                        JOIN canonical_units u ON u.chain_ref = d.chain_ref
                         AND u.position_scope = d.inclusion_scope
                         AND u.position = d.inclusion_position AND u.unit_hash = d.inclusion_hash
                        WHERE d.chain_ref = ? AND u.position_scope = ? AND d.state = ?
                                                    AND u.id %s ?
                                                    %s
                        FOR UPDATE
                                                """.formatted(comparison, canonicalCondition),
                (resultSet, rowNumber) -> new LifecycleDeposit(
                        resultSet.getLong("id"), resultSet.getString("account_id"),
                        resultSet.getString("asset_ref"), resultSet.getString("amount_raw"),
                        DepositState.valueOf(resultSet.getString("state"))),
                chain.toString(), scope, state.name(), boundaryId);
    }

    private void applyLifecycle(LifecycleDeposit value, boolean reversal) {
        DepositState required = reversal ? DepositState.CREDITED : DepositState.FINALITY_READY;
        if (value.state() != required) {
            throw new IllegalArgumentException("invalid deposit lifecycle state: " + value.state());
        }
        String prefix = reversal ? "deposit-reversal" : "deposit-credit";
        String entryType = reversal ? "deposit_reversal" : "deposit_credit";
        String ledgerTransactionId = prefix + ":" + value.id();
        String accountDirection = reversal ? "debit" : "credit";
        String clearingDirection = reversal ? "credit" : "debit";
        insertLedgerEntry(value, ledgerTransactionId, value.accountId(), accountDirection, entryType, "account");
        insertLedgerEntry(value, ledgerTransactionId, DEPOSIT_CLEARING_ACCOUNT, clearingDirection, entryType, "clearing");
        if (reversal) {
            insertStateChange(value.id(), DepositState.CREDITED, DepositState.REVERSAL_PENDING, "canonical_reorg");
            insertStateChange(value.id(), DepositState.REVERSAL_PENDING, DepositState.REVERSED, "canonical_reorg");
        } else {
            insertStateChange(value.id(), DepositState.FINALITY_READY, DepositState.CREDITED, "ledger_credit");
        }
        DepositState finalState = reversal ? DepositState.REVERSED : DepositState.CREDITED;
        int rows = jdbc.update("""
                UPDATE deposit_observations
                SET state = ?,
                    credited_at = IF(? = 'CREDITED', UTC_TIMESTAMP(6), credited_at),
                    reorged_at = IF(? = 'REVERSED', UTC_TIMESTAMP(6), reorged_at)
                WHERE id = ? AND state = ?
                """, finalState.name(), finalState.name(), finalState.name(), value.id(), required.name());
        if (rows != 1) {
            throw new IllegalStateException("deposit changed during lifecycle update: " + value.id());
        }
        String eventType = reversal ? "deposit.reversed" : "deposit.credited";
        String payload = writeJson(Map.of(
                "observation_id", Long.toString(value.id()),
                "account_id", value.accountId(),
                "asset_ref", value.assetRef(),
                "amount_raw", value.amountRaw(),
                "state", finalState.name()), "deposit lifecycle payload");
        jdbc.update("""
                INSERT INTO outbox_events
                    (event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at, status)
                VALUES (?, 'deposit', ?, ?, ?, UTC_TIMESTAMP(6), 'pending')
                ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
                """, ledgerTransactionId, Long.toString(value.id()), eventType, payload);
    }

    private void insertLedgerEntry(
            LifecycleDeposit value,
            String ledgerTransactionId,
            String accountId,
            String direction,
            String entryType,
            String side) {
        jdbc.update("""
                INSERT INTO ledger_entries
                    (ledger_tx_id, account_id, asset_ref, amount_raw, direction, entry_type,
                     reference_type, reference_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 'deposit_observation', ?, ?)
                ON DUPLICATE KEY UPDATE idempotency_key = VALUES(idempotency_key)
                """,
                ledgerTransactionId, accountId, value.assetRef(), value.amountRaw(), direction,
                entryType, Long.toString(value.id()), ledgerTransactionId + ":" + side);
    }

    private void insertStateChange(long id, DepositState from, DepositState to, String reason) {
        jdbc.update("""
                INSERT INTO deposit_state_history(observation_id, from_state, to_state, reason)
                VALUES (?, ?, ?, ?)
                """, id, from.name(), to.name(), reason);
    }

    private void insertOutbox(List<OutboxEvent> events) {
        for (OutboxEvent event : events) {
            String payload = new String(event.payload(), StandardCharsets.UTF_8);
            try {
                mapper.readTree(payload);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("outbox payload must be valid JSON", exception);
            }
            jdbc.update("""
                    INSERT INTO outbox_events
                        (event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'pending')
                    """,
                    event.id(), event.aggregateType(), event.aggregateId(), event.eventType(),
                    payload, Timestamp.from(event.occurredAt()));
        }
    }

    private PointValues pointValues(ChainPoint point) {
        List<PersistedPointRef> parents = point.parents().stream()
                .map(parent -> new PersistedPointRef(parent.scope(), parent.position(), parent.hash()))
                .toList();
        return new PointValues(
                point.scope(), point.position(), point.hash(),
                writeJson(parents, "chain point parents"),
                point.time() == null ? null : Timestamp.from(point.time()));
    }

    private String writeJson(Object value, String description) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("encode " + description, exception);
        }
    }

    private static String requireMetadata(DepositObservation observation, String key) {
        String value = observation.metadata().get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("observation metadata requires " + key);
        }
        return value;
    }

    private ScanCursor mapCursor(ResultSet resultSet, ChainRef chain, String scannerId)
            throws SQLException {
        String position = resultSet.getString("position");
        String hash = resultSet.getString("unit_hash");
        ChainPoint point = position == null && hash == null ? null : mapPoint(resultSet);
        return new ScanCursor(chain, scannerId, point, resultSet.getLong("version"));
    }

    private ChainPoint mapPoint(ResultSet resultSet) throws SQLException {
        Timestamp time = resultSet.getTimestamp("unit_time");
        return new ChainPoint(
                Objects.requireNonNullElse(resultSet.getString("position_scope"), ""),
                resultSet.getString("position"),
                resultSet.getString("unit_hash"),
                decodeParents(resultSet.getString("parents_json")),
                time == null ? null : time.toInstant());
    }

    private List<PointRef> decodeParents(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(value, PARENT_LIST).stream()
                    .map(parent -> new PointRef(parent.scope(), parent.position(), parent.hash()))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new SQLException("decode cursor parents", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private record VersionedWatchTarget(WatchTarget target, long version) {
    }

    private record PersistedPointRef(
            @JsonProperty("Scope") String scope,
            @JsonProperty("Position") String position,
            @JsonProperty("Hash") String hash) {
    }

        private record PointValues(
            String scope,
            String position,
            String hash,
            String parentsJson,
            Timestamp time) {
        }

        private record PersistedObservation(
            String assetRef,
            String amountRaw,
            String toTarget,
            String ownerId,
            String accountId,
            DepositState state) {
        }

        private record LifecycleDeposit(
            long id,
            String accountId,
            String assetRef,
            String amountRaw,
            DepositState state) {
        }
}