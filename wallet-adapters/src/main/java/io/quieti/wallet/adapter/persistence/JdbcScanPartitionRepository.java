package io.quieti.wallet.adapter.persistence;

import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ScanLease;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcScanPartitionRepository implements ScanPartitionRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcScanPartitionRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public ScanLease acquire(
            String partitionId,
            String chain,
            String ownerId,
            ChainCheckpoint initialCheckpoint,
            Instant now,
            Duration leaseDuration) {
        return transactions.execute(status -> {
            List<ScanLease> existing = findForUpdate(partitionId);
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO scan_partition
                            (partition_id, chain, owner_id, lease_version, lease_until,
                             checkpoint_height, checkpoint_hash, updated_at)
                        VALUES (?, ?, NULL, 0, ?, ?, ?, ?)
                        """,
                        partitionId,
                        chain,
                        Timestamp.from(Instant.EPOCH),
                        initialCheckpoint.height(),
                        initialCheckpoint.blockHash(),
                        Timestamp.from(now));
                existing = findForUpdate(partitionId);
            }

            ScanLease current = existing.getFirst();
            if (!current.chain().equals(chain)) {
                throw new IllegalArgumentException("Partition is already assigned to another chain");
            }
            if (current.leaseUntil().isAfter(now) && !current.ownerId().equals(ownerId)) {
                throw new IllegalStateException("Partition lease is held by another owner");
            }

            long version = current.ownerId().equals(ownerId) && current.leaseUntil().isAfter(now)
                    ? current.leaseVersion()
                    : current.leaseVersion() + 1;
            Instant leaseUntil = now.plus(leaseDuration);
            jdbc.update("""
                    UPDATE scan_partition
                    SET owner_id = ?, lease_version = ?, lease_until = ?, updated_at = ?
                    WHERE partition_id = ?
                    """,
                    ownerId,
                    version,
                    Timestamp.from(leaseUntil),
                    Timestamp.from(now),
                    partitionId);
            return new ScanLease(
                    partitionId, chain, ownerId, version, leaseUntil, current.checkpoint());
        });
    }

    @Override
    public boolean commit(ScanLease lease, ChainBlock block, Instant now) {
        Boolean committed = transactions.execute(status -> {
            int rows = jdbc.update("""
                    UPDATE scan_partition
                    SET checkpoint_height = ?, checkpoint_hash = ?, updated_at = ?
                    WHERE partition_id = ?
                      AND owner_id = ?
                      AND lease_version = ?
                      AND lease_until > ?
                      AND checkpoint_height = ?
                      AND checkpoint_hash = ?
                    """,
                    block.height(),
                    block.hash(),
                    Timestamp.from(now),
                    lease.partitionId(),
                    lease.ownerId(),
                    lease.leaseVersion(),
                    Timestamp.from(now),
                    lease.checkpoint().height(),
                    lease.checkpoint().blockHash());
            if (rows == 0) {
                return false;
            }
            jdbc.update("""
                    INSERT INTO chain_block (chain, height, block_hash, parent_hash, observed_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    block.chain(),
                    block.height(),
                    block.hash(),
                    block.parentHash(),
                    Timestamp.from(now));
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private List<ScanLease> findForUpdate(String partitionId) {
        return jdbc.query("""
                        SELECT partition_id, chain, owner_id, lease_version, lease_until,
                               checkpoint_height, checkpoint_hash
                        FROM scan_partition
                        WHERE partition_id = ?
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> new ScanLease(
                        resultSet.getString("partition_id"),
                        resultSet.getString("chain"),
                        resultSet.getString("owner_id") == null ? "unowned" : resultSet.getString("owner_id"),
                        Math.max(1, resultSet.getLong("lease_version")),
                        resultSet.getTimestamp("lease_until").toInstant(),
                        new ChainCheckpoint(
                                resultSet.getLong("checkpoint_height"),
                                resultSet.getString("checkpoint_hash"))),
                partitionId);
    }
}