# Shared database schema

The unified scanner schema is owned by `G:\github\goWallet\deploy\sql\init.sql`.
This Java service consumes the existing `chains`, `assets`, `watch_targets`,
`scan_cursors`, `canonical_units`, `deposit_observations`,
`deposit_state_history`, `ledger_entries`, and `outbox_events` tables.

Flyway is disabled for this service. Do not add Java-owned migrations for these
tables while Go and Java share the database. Schema changes must be applied by
the goWallet migration process and verified by the MySQL compatibility tests in
`wallet-adapters` before deployment.