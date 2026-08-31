# Wallet Service

Java 21 LTS and Spring Boot wallet service scaffold based on the architecture and safety constraints in `day26.md`.

## Modules

- `wallet-domain`: immutable chain, amount, checkpoint and signing value objects; no Spring or persistence dependencies.
- `wallet-application`: scan use case and outbound ports. It enforces parent-hash continuity and fencing-token commits.
- `wallet-adapters`: node and persistence adapters. The deterministic node is for local verification only.
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

The local profile uses an in-memory H2 database in MySQL compatibility mode. Trigger two consecutive blocks:

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

The current node adapter generates deterministic blocks to exercise ordering, lease and checkpoint behavior. Before production, replace it with per-chain RPC adapters and add provider-specific deadlines, retries, circuit breakers, rate limits, canonical transaction parsing, Outbox/MQ, withdrawal state machines and an isolated HSM/MPC signer. The service does not hold private keys or perform real transfers.