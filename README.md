# fx-ledger

A multi-currency ledger with locked FX quotes and idempotent transfers.

Work in progress. The design is in [docs/specs](docs/specs/2026-09-25-fx-ledger-design.md)
and the build order in [docs/plans](docs/plans/2026-09-25-fx-ledger-implementation-plan.md).

## Stack

Java 25, Spring Boot 4.1.1, Gradle 9.7.1, PostgreSQL 18.

## Build

Requires JDK 25 and Docker (tests start a real PostgreSQL through Testcontainers).

```bash
./gradlew build
```
