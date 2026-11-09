# Plan: move persistence from JdbcClient to jOOQ

> **Status:** Implemented on branch `jooq-migration`, one commit per phase.
> **Author:** Jader Santos.
> **Companion files:** [`SDD.md`](SDD.md) (§6.3 layout, §6.6 messaging, §6.7 data model, §9 testing).
> **Source of truth:** the store classes listed in [§3](#3-scope), Boot BOM `spring-boot-dependencies` 4.1.2.
> **Open questions:** see [§10](#10-open-questions).

---

## 1. TL;DR

Every query in the repo is a `JdbcClient` string with hand-written row mapping. Ten store classes across four modules change to jOOQ, with code generated from a real Postgres migrated by Flyway and committed. The schema, the migrations, the transaction boundaries, the saga domain and the Kafka contracts stay the same. The existing Testcontainers ITs and `make e2e` are the regression net, and the work lands one module per commit on one branch.

---

## 2. Why

* SQL is checked only at runtime. A renamed column fails in an IT or in production, never at compile time.
* Row mapping is manual: `rs.getString("status")` then `SagaStatus.valueOf(...)`, repeated per store.
* SonarCloud flags `java:S2077` on `RiskStore.java:64`, where the `SET` clause is concatenated. It is a false positive, but string SQL keeps producing that class of finding.

---

## 3. Scope

| Module | Class | Queries today | Postgres features in use |
|---|---|---|---|
| `messaging` | `OutboxWriter` | 1 insert | - |
| `messaging` | `OutboxRelay` | advisory lock, select, delete | `pg_try_advisory_xact_lock`, `FOR UPDATE SKIP LOCKED`, `IN (:ids)` |
| `messaging` | `OutboxMetrics` | 2 gauges | `extract(epoch FROM now() - min(...))` |
| `messaging` | `StepRecords` | lock, insert, update | `FOR UPDATE` |
| `transfer-service` | `SagaStore` | insert, update, lock, find, due ids, lock if due, postpone | `?::jsonb`, `FOR UPDATE`, `FOR UPDATE SKIP LOCKED` |
| `transfer-service` | `SagaEvents` | insert per event | - |
| `transfer-service` | `TransferViews` | summary, steps, events (3 queries per view) | - |
| `transfer-service` | `SagaMetrics` | 3 gauges | `extract(epoch ...)`. The queries move into `SagaStore`, see [§5.5](#55-guard-rails) |
| `account-service` | `AccountStore` | all, find, debit, credit, correct, entry, empty | conditional `UPDATE ... WHERE balance >= :amount` |
| `risk-service` | `RiskStore` | reserve, clear, unclear, release, watchlist, usage, empty | `ON CONFLICT DO NOTHING`, `UPDATE ... FROM`, `UNION`, `CAST` |

Out of scope:

* `AccountStore.reset()` and `RiskStore.reset()` keep `ResourceDatabasePopulator` running `db/seed/reset.sql`. That is a seed script, not query code.
* Test helpers that use `JdbcClient` (`OutboxRelayIT`, `ParticipantRuntimeIT`, `FakeHandler`). They can stay as they are, since `JdbcClient` stays on the classpath.

## 4. Non-goals

* No schema change and no new Flyway migration.
* No change to transaction boundaries, lock order or isolation level.
* No change to `TransferSaga`, `Transition` or any type in `messaging.contract`.
* No JPA, no Spring Data repositories.
* No change to `web/`.

---

## 5. Design

### 5.1 Dependencies

| Module | Today | After |
|---|---|---|
| `messaging` | `spring-boot-starter-jdbc` | `spring-boot-starter-jooq` (brings JDBC, Hikari and `JdbcClient` with it) |
| services | `messaging` | unchanged, they get jOOQ through `messaging` |
| test scope, every module | - | `jooq-codegen` and `jooq-meta` at `${jooq.version}` from the Boot BOM (3.21.9), used only by the generator |
| build | - | `build-helper-maven-plugin` adds `src/generated/java` as a source root |

Pinning the generator to `${jooq.version}` keeps generated code and runtime on the same jOOQ release. A mismatch between the two is a compile or runtime error.

### 5.2 Code generation

Each module generates classes only for the tables it owns:

| Module | Migrations read | Generated package |
|---|---|---|
| `messaging` | `db/messaging`, `db/participant` | `dev.jaderss.saga.messaging.db` |
| `transfer-service` | `db/migration` | `dev.jaderss.saga.transfer.persistence.db` |
| `account-service` | `db/migration` | `dev.jaderss.saga.account.db` |
| `risk-service` | `db/migration` | `dev.jaderss.saga.risk.db` |

Generation runs against a real Postgres, never from parsed DDL:

1. `JooqCodegen`, a class in each module's `src/test/java`, starts the Postgres container from `SagaContainers`.
2. It runs Flyway with that module's locations, in the same order the service uses.
3. It calls `org.jooq.codegen.GenerationTool` against that database and writes to `src/generated/java`.

`make jooq` runs it for all four modules as an explicitly selected test, `-Dtest=JooqCodegen`, so no extra Maven plugin is needed. The class name has no `Test` suffix, so a normal build never runs it. The generated sources are committed, so `compile` and the IDE need no Docker. Only `make jooq` and `verify` do, and `verify` already needs Docker for the ITs.

`make jooq-check` runs `make jooq` and then `git diff --exit-code -- '*/src/generated'`. A migration added without regenerating fails that check.

Postgres types come out exact: `saga.faults` is `JSONB`, `TIMESTAMPTZ` is `OffsetDateTime`, and the partial index `saga_due` exists in the generated metadata. jOOQ's `DDLDatabase` was the first idea. It was dropped because it translates the scripts to an in-memory H2, which has no `JSONB` and no partial indexes (see [§9.6](#96-generate-from-ddl-with-ddldatabase)).

Forced types in the codegen config:

| Column | Java type | How |
|---|---|---|
| `saga.status`, `saga_step.status`, `saga.current_step`, `saga.current_action`, `step_record.status`, `step_record.step`, `account.status` | the existing enums | `EnumConverter` |
| `TIMESTAMPTZ` | `OffsetDateTime` | jOOQ default with `javaTimeTypes` |
| `NUMERIC(19,2)` | `BigDecimal` | jOOQ default |
| `saga.faults` | `JSONB` | jOOQ default, serialized with the existing `JsonMapper` |

The enum converters remove every `.name()` and `valueOf(...)` call from the stores. Generated code depends on the domain enums. The domain never depends on generated code.

Generated code lives in its own package, so every converter target must be `public`. `AccountStatus` is package-private today and becomes `public` in phase 0. `EntryKind` stays package-private, since `account_entry.kind` has no forced type.

### 5.3 Runtime wiring

Boot's jOOQ auto-configuration builds the `DSLContext` on a `TransactionAwareDataSourceProxy`. jOOQ statements and `JdbcClient` statements therefore join the same Spring transaction. Two consequences:

* `OutboxWriter.append` keeps `@Transactional(propagation = MANDATORY)` and still writes in the caller's transaction.
* A module can move one class at a time. Mixed `JdbcClient` and jOOQ code inside one transaction is safe during the migration.

Configuration added to each `application.yaml`:

```yaml
spring:
  jooq:
    sql-dialect: postgres
```

`MessagingAutoConfiguration` and `ParticipantAutoConfiguration` take a `DSLContext` instead of a `JdbcClient` and declare `after = JooqAutoConfiguration.class`.

The JVM flags `-Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true` go into the Dockerfile and the Surefire/Failsafe `argLine`. They keep the startup banner out of the logs.

### 5.4 Query mapping

| Today | jOOQ |
|---|---|
| `SELECT ... FOR UPDATE SKIP LOCKED` | `.forUpdate().skipLocked()` |
| `INSERT ... ON CONFLICT DO NOTHING` | `.onConflictDoNothing()` |
| `UPDATE limit_usage u SET ... FROM reservation r WHERE ...` | `.update(LIMIT_USAGE).set(...).from(RESERVATION).where(...)` |
| `DELETE FROM outbox WHERE id IN (:ids)` | `.deleteFrom(OUTBOX).where(OUTBOX.ID.in(ids))` |
| `?::jsonb` | `JSONB.valueOf(json.writeValueAsString(faults))` |
| `.query(Account.class)` | `.fetchInto(Account.class)` (record constructor mapping) |
| `pg_try_advisory_xact_lock(hashtext('outbox-relay'))` | plain SQL template with a constant: `field("pg_try_advisory_xact_lock(hashtext({0}))", Boolean.class, inline(LEAD_KEY))` |
| `extract(epoch FROM now() - min(created_at))` | plain SQL template with a constant, same as today |
| 3 queries per `TransferView` | one query with `multiset(...)` for steps and events, mapped to `StepView` and `EventView` |

`RiskStore.onReservation` takes a `Map<Field<BigDecimal>, Field<BigDecimal>>` of typed assignments instead of a SQL string. `clear`, `unclear` and `release` each pass their own map. That removes the `S2077` finding.

### 5.5 Guard rails

* **ArchUnit.** Only `transfer-service` has an `ArchitectureTest`, so the rules live there. `domain_is_plain_java` adds `org.jooq..` to its forbidden packages. A new rule allows `org.jooq..` only in `..transfer.persistence..`, which also holds the generated package. `messaging`, `account-service` and `risk-service` keep their stores in one package each and get no new ArchUnit test.
* **Gauges.** `SagaMetrics` sits in `transfer.app`, so it cannot use jOOQ under that rule. Its queries move into `SagaStore` as `count(SagaStatus)` and `secondsSinceOldestIn(SagaStatus)`. `SagaMetrics` stays a plain `MeterBinder` that calls them.
* **Partial index.** `saga_due` has the predicate `status IN ('RUNNING', 'COMPENSATING')`. jOOQ binds values by default, and a generic Postgres plan cannot match a bind parameter to a partial index predicate. `SagaStore` keeps those two statuses as inline literals in `dueIds` and `lockIfDue`.
* **Logging.** jOOQ's `LoggerListener` logs every statement with inlined bind values at `DEBUG`. Those values include account ids and amounts. `org.jooq` stays at `INFO` in every `application.yaml`, with an explicit `logging.level.org.jooq.tools.LoggerListener: INFO` so a later package-wide `DEBUG` does not turn it on by accident.
* **Sonar.** The Maven scanner reads compile source roots, and `src/generated/java` becomes one. `sonar.exclusions` and `sonar.coverage.exclusions` get `**/src/generated/**`. JaCoCo excludes the generated packages from the report.
* **Spotless.** The include pattern `src/*/java/**/*.java` matches `src/generated/java`. An `<excludes>` entry for `src/generated/**` keeps the formatter off generated code.

---

## 6. Phases

One branch, one commit per phase, one PR at the end. Every phase ends with a green `./mvnw verify` and a SonarCloud scan with no new issues.

| Phase | Change | Gate |
|---|---|---|
| **0. Build** | Starter swap in `messaging`. `JooqCodegen`, forced types, `make jooq` and `make jooq-check` in all four modules, generated sources committed. `AccountStatus` made `public`. Dialect, logging, Sonar and Spotless exclusions. ArchUnit rules. No query changes. | Build green. `make jooq-check` clean. Existing tests pass unchanged. |
| **1. risk-service** | `RiskStore` on jOOQ. | `RiskStepsIT` green. `S2077` gone in Sonar. |
| **2. account-service** | `AccountStore` on jOOQ. | `AccountStepsIT` green. |
| **3. messaging** | `OutboxWriter`, `OutboxRelay`, `OutboxMetrics`, `StepRecords` on jOOQ. Auto-configurations take `DSLContext`. | `OutboxRelayIT` (including the ordering test across concurrent relays) and `ParticipantRuntimeIT` green. |
| **4. transfer-service** | `SagaStore`, `SagaEvents` and `TransferViews` (with `multiset`) on jOOQ. Gauge queries move from `SagaMetrics` into `SagaStore`. | `TransferFlowIT`, `TransferSagaTest`, property tests and `ArchitectureTest` green. `TransferFlowIT` asserts the event order and the `at` format of `EventView`. |
| **5. Clean-up** | No `JdbcClient` left in `src/main`. Update `README.md` line 153 and `SDD.md` §6.3, §7.1 and §11 (add jOOQ as decided, JDBI and Spring Data JDBC as alternatives). | `make up && make e2e` green: every scenario, both balances per transfer, total of all balances, no reservation left behind. |

Phase 1 goes before `messaging` on purpose. `RiskStore` is the smallest store with the most Postgres-specific SQL, so it proves the codegen setup and the dialect before the shared module changes.

---

## 7. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Committed generated code drifts from the migrations | med | med | `make jooq-check` is part of every phase gate. The ITs run on the real migrated schema and fail on any mismatch that slips through. |
| A lock clause changes meaning (`FOR UPDATE` vs `FOR UPDATE SKIP LOCKED`) | low | high | Lock queries map one to one per [§5.4](#54-query-mapping). `OutboxRelayIT` ordering test and `TransferFlowIT` timeout test cover both clauses. |
| Generated code inflates Sonar LOC and lowers coverage | high without exclusions | low | Exclusions in [§5.5](#55-guard-rails), set in phase 0 before any generated code exists. |
| Bind values with account ids reach logs at `DEBUG` | low | high | Explicit `INFO` level per [§5.5](#55-guard-rails). |
| Gauge queries break without a test noticing | med | low | No test reads `outbox.depth`, `outbox.oldest.age`, `saga.active` or `saga.compensating.oldest.age` today. Phase 3 and 4 each add one assertion on the `MeterRegistry` value. |
| `multiset` changes the order or the timestamp format of nested rows | low | med | Postgres builds nested rows as JSON, so `timestamptz` goes through text. The records stay the same, and the phase 4 gate asserts event order and the `at` format. |

---

## 8. Rollback

Each phase is one commit. Reverting a phase commit restores `JdbcClient` for that module only, since both styles share the same transaction ([§5.3](#53-runtime-wiring)). Phase 0 can stay in place on its own: it adds generated classes and changes no behavior.

---

## 9. Alternatives considered

### 9.1 Keep JdbcClient, fix only RiskStore

**What:** three `static final` SQL constants in `RiskStore`, nothing else.
**Why not:** clears one Sonar finding and leaves the cause, string SQL and manual mapping in ten classes.

### 9.2 JDBI 3

**What:** fluent API and SQL Object interfaces with `@SqlQuery`, constructor mapping into records.
**Why not:** SQL stays in strings with no compile-time check. Joining Spring transactions needs the `jdbi3-spring` bridge, one more piece the outbox `MANDATORY` propagation would depend on.

### 9.3 Spring Data JDBC

**What:** aggregate repositories, records supported, no new dependency.
**Why not:** saving an aggregate deletes and re-inserts its child rows, so every saga transition would rewrite all `saga_step` rows. No `SKIP LOCKED` and no advisory lock, so the relay and participant locks would stay on `JdbcClient`, which leaves two styles.

### 9.4 Spring Data JPA

**What:** Hibernate entities and repositories.
**Why not:** entities cannot be records, so a second model and a mapping layer would sit beside the domain records. Flush ordering hides when SQL runs, which the outbox depends on. `SKIP LOCKED` and the advisory lock need hints and native queries.

### 9.5 MyBatis

**What:** SQL in XML or annotations with result maps.
**Why not:** SQL is still unchecked strings, and it adds a starter maintained outside the Spring team.

### 9.6 Generate from DDL with DDLDatabase

**What:** `jooq-meta-extensions` parses the Flyway scripts at build time. No database, no committed sources.
**Why not:** it translates the scripts to an in-memory H2 and generates from H2. H2 has no `JSONB` and no partial indexes, so `saga.faults` and `saga_due` would come out wrong or not at all. Every future Postgres-specific migration would carry the same risk.

---

## 10. Open questions

| # | Question | Why it matters | Default assumed in this doc |
|---|---|---|---|
| Q1 | Generate at build time from DDL, or commit code generated from a real Postgres? | Build time keeps the repo clean. Committed code is exact to Postgres and needs no parser. | Committed code from a real Postgres, guarded by `make jooq-check` ([§5.2](#52-code-generation)). |
| Q2 | Map status and step columns to enums in codegen? | Removes `valueOf` calls, but couples generated code to domain enum names. | Yes, with `EnumConverter`. |
| Q3 | Use `multiset` for `TransferView`? | One query instead of three, but a less familiar jOOQ feature. | Yes. |
| Q4 | Move the test helpers off `JdbcClient` too? | Consistency against extra churn in tests that already work. | No, they stay on `JdbcClient`. |
| Q5 | One PR, or one PR per phase? | Review size against merge overhead. | One PR with one commit per phase. |
