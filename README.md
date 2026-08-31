# Wallet Service

Java 21 LTS and Spring Boot implementation of the Go-compatible four-chain deposit scanner for Bitcoin Testnet/Signet, EVM, Solana and TON.

## Modules

- `wallet-domain`: immutable chain, amount, checkpoint and signing value objects; no Spring or persistence dependencies.
- `wallet-application`: protocol-neutral scanner contracts, coordinator, finality and shadow comparison flow.
- `wallet-adapters`: chain RPC clients and the Go-schema-compatible MySQL repository.
- `wallet-bootstrap`: Spring wiring, validated configuration, bounded scan executor, HTTP API, health probes and lifecycle settings.

## Prerequisites

- JDK 21 LTS (`java -version` must report 21)
- Maven 3.9+

On this machine JDK 21 is installed at `C:\Program Files\Java\jdk-21.0.9`. Set `JAVA_HOME` before building if another JDK is the default.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.9'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean verify
java -jar wallet-bootstrap\target\wallet-bootstrap-0.1.0-SNAPSHOT.jar
```

The local profile uses an in-memory H2 database in MySQL compatibility mode. Trigger two deterministic legacy scan blocks:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/scans/evm/evm-main/next
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/scans/evm/evm-main/next
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Production profile

Use MySQL 8 and inject credentials through the environment. Do not commit production credentials.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:WALLET_DB_URL = 'jdbc:mysql://mysql:3306/wallet'
$env:WALLET_DB_USERNAME = 'wallet'
$env:WALLET_DB_PASSWORD = '<from-secret-manager>'
java -jar wallet-bootstrap\target\wallet-bootstrap-0.1.0-SNAPSHOT.jar
```

The unified scanners are disabled by default. Enable only the required chain scanners and provide trusted start checkpoints and provider credentials. The service does not hold private keys or perform transfers.

## Shadow verification

Shadow mode runs the Java adapters against the configured providers and compares their canonical units and deposit observations with the Go-owned rows in MySQL. It uses an independent `<scanner-id>-shadow` in-memory cursor initialized from the adapter's trusted checkpoint. It never calls Commit, Rollback or AdvanceFinality, so it cannot write scan cursors, lifecycle history, ledger entries or Outbox events.

Keep the Go scanner active while shadow mode is running:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:WALLET_SCANNER_SHADOW_MODE = 'true'
$env:WALLET_SCANNERS_BTC_ENABLED = 'true'
java -jar wallet-bootstrap\target\wallet-bootstrap-0.1.0-SNAPSHOT.jar
```

Inspect counters through Actuator:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/metrics/wallet.shadow.batches
Invoke-RestMethod http://localhost:8080/actuator/metrics/wallet.shadow.items
Invoke-RestMethod http://localhost:8080/actuator/metrics/wallet.shadow.differences
```

Counters use only `chain`, `scanner`, `result` and `kind` tags. Every difference also emits a structured `Shadow scan semantic difference` warning with position, expected value and actual value. A Java restart intentionally replays shadow verification from the trusted checkpoint because shadow state is never persisted.

Do not cut over until all enabled chains have completed the agreed observation window, `wallet.shadow.differences` has not increased, every batch reports `result=match`, and provider/checkpoint configuration has been independently reviewed.

## Single-active cutover

Only one normal scanner may own a `(chain_ref, scanner_id)` at a time. Shadow mode is read-only and is the only supported period in which Go and Java scanners may run concurrently.

1. Record the Go scanner cursor point and version for every chain. Record pending/failed Outbox counts and the reconciliation queries below.
2. Stop the Java shadow process, then stop and verify termination of every Go scanner worker.
3. Confirm no cursor version changes for at least two normal poll intervals. Do not continue if any cursor still moves.
4. Start exactly one Java instance with `WALLET_SCANNER_SHADOW_MODE=false` and only the approved chain scanner flags enabled.
5. Verify that Java loads the existing Go cursor and advances its version by CAS. Check scanner errors, cursor lag, Outbox publication and balanced ledger entries before enabling another chain.
6. Preserve the captured pre-cutover evidence and monitor through the full finality window.

Never start normal Java scanning while a Go worker is active. CAS prevents silent cursor overwrite, but repeated contention is an operational fault, not a leader-election mechanism.

## Rollback

1. Stop all Java scanner instances and verify cursor versions have stopped changing.
2. Capture current cursors, noncanonical units, deposit lifecycle states, ledger totals and pending/failed Outbox rows. Do not delete or rewind rows.
3. Restart exactly one Go scanner per logical scanner ID against the same MySQL database. It must resume from the Java-written cursor/version.
4. Confirm the first Go cycle succeeds without stale-cursor retries and that canonical verification completes.
5. Run reconciliation again. Resolve missing Outbox publication through the existing idempotent publisher; never recreate ledger rows manually.

If either implementation cannot parse the current cursor or canonical history, keep both scanners stopped and restore service from the captured database backup plus audited reconciliation. Do not bypass CAS or edit cursor versions in place.

## Reconciliation

Run these read-only checks before cutover, after cutover, and after rollback:

```sql
SELECT chain_ref, scanner_id, position_scope, position, unit_hash, version
FROM scan_cursors ORDER BY chain_ref, scanner_id;

SELECT chain_ref, position_scope, position, COUNT(*) AS canonical_count
FROM canonical_units WHERE canonical = 1
GROUP BY chain_ref, position_scope, position HAVING COUNT(*) <> 1;

SELECT chain_ref, transaction_kind, transaction_ref, source, COUNT(*) AS duplicate_count
FROM deposit_observations
GROUP BY chain_ref, transaction_kind, transaction_ref, source HAVING COUNT(*) <> 1;

SELECT entry_type, asset_ref,
	   SUM(CASE WHEN direction = 'credit' THEN CAST(amount_raw AS SIGNED)
				ELSE -CAST(amount_raw AS SIGNED) END) AS imbalance
FROM ledger_entries GROUP BY entry_type, asset_ref HAVING imbalance <> 0;

SELECT status, COUNT(*) FROM outbox_events GROUP BY status;
```

Also compare per-chain counts grouped by deposit state, the highest canonical position, reorged-unit counts and Outbox event types between the pre-change and post-change snapshots. Any unexplained difference blocks cutover completion.