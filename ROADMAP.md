# pool-warden — Road to release

> Snapshot: 2026-09-22. Version `0.1.0-SNAPSHOT`. The repo is public at
> [rizkywahyus/spring-pool-warden](https://github.com/rizkywahyus/spring-pool-warden); `v0.1.0`
> gets tagged once CI is green.

## 1. Current status

| Area | Status | Notes |
|---|---|---|
| `WardenDataSource` + `WardenConnectionHandler` proxy | ✅ | Only `Connection` is proxied, not `Statement`/`ResultSet` |
| `ConnectionTracker` + `TrackedEntry` | ✅ | Keyed by id; each entry carries its data source name |
| `PoolSweeper` in `WARN_ONLY` mode | ✅ | Warns once per leak |
| `REAP` mode + CAS for the close-vs-reap race | ✅ | **Pool capacity actually comes back** — see §2 |
| Call-site capture via `StackWalker` | ✅ | Filters Spring AOP/TX/proxy frames; can be turned off |
| Spring Boot starter (auto-config, properties, BPP) | ✅ | Boot 3.5.16, Java 17 baseline |
| Micrometer metrics | ✅ | 4 meters, all tagged with `datasource` |
| Actuator endpoint `/actuator/poolwarden` | ✅ | Read-only, shows the data source of each connection |
| Multiple data sources | ✅ | Per-bean tag; skips `DelegatingDataSource`/routing data sources |
| Demo app (H2 + Hikari) | ✅ | |
| README with honest trade-offs | ✅ | Includes JMH numbers |
| Tests | ✅ 50 tests | `./mvnw clean verify` green locally; coverage core 86%, starter 93% |
| HikariCP / Tomcat JDBC / DBCP2 matrix | ✅ | Capacity-recovery test for all three |
| Testcontainers PostgreSQL | ⚠️ | Written, but never passed yet: skipped locally (no Docker), suspected cause of the CI failure |
| JMH benchmark | ✅ | `pool-warden-benchmark` module |
| LICENSE + CI (JDK 17 & 21) + jitpack.yml + CHANGELOG | ✅ | |
| GitHub repo + push `main` | ✅ | `rizkywahyus/spring-pool-warden` |
| CI green on GitHub | ⏳ | First run failed: `PostgresReapTest` exposed a real reap bug with async-abort drivers (fixed, see §2) |
| Release tag `v0.1.0` + JitPack | ⬜ | Blocked on CI |
| Spring Boot 4 | ⬜ | See §3 Milestone 4 |

## 2. P0 resolved

**REAP did not free the pool slot.** `ConnectionReaper` used to call only `abort()`. The physical
connection died, but the pool kept counting the slot as `IN_USE` until `close()` was called on the
connection the *pool* handed out — and in a real leak the application never calls it. Result: the
leak was dead, the pool was still exhausted.

The fix (option A, pool-agnostic): after `abort()`, the reaper calls `close()` on the pool-level
connection. The pool takes its slot back and then discards the dead connection through its own
validation, so a `close()` that fails after an abort is the expected outcome, not an error — it is
logged at debug level. As a consequence, `WardenConnectionHandler.close()` no longer forwards
`close()` once the entry has been reaped, so the pool never sees a double release.

Proof: `PoolCapacityRecoveryTest` (HikariCP, Tomcat JDBC, DBCP2) and `PostgresReapTest`
(PostgreSQL 16 via Testcontainers) — both assert that the next `getConnection()` succeeds, not
merely that `abort()` was called.

**Async abort handed the pool a dead connection.** Found by the first CI run of `PostgresReapTest`:
pgjdbc's `abort()` only schedules the teardown on the executor and returns, so the reaper's
`close()` reached HikariCP while the connection still looked healthy. HikariCP recycled it, skipped
its liveness check (the connection was used under 500 ms ago) and lent it out just as the teardown
killed it: `This connection has been closed.` H2 aborts synchronously, which is why the H2-based
tests never saw it. The reaper now waits (at most 5 s) for the driver's teardown before closing;
`PoolSweeperReapTest.waitsForAnAsynchronousAbortBeforeReturningTheSlot` pins the ordering.

Other findings resolved along the way: P1.3 double wrapping, P1.4 `datasource` tag, P1.5 Java 17
baseline, P2.1 sweeper shutdown order, P2.2 virtual thread names, P2.3 framework frame filter,
P2.5 ignored `ageMillis`.

## 3. Remaining work

### Milestone 3 — Release `v0.1.0`
- [x] Create the GitHub repo `rizkywahyus/spring-pool-warden` and push `main`. The username
      matches `groupId io.github.rizkywahyus`, which Maven Central will require later.
- [ ] Get CI green on JDK 17 and 21, including the Testcontainers PostgreSQL test. The first run
      failed on both JDKs in `PostgresReapTest` (async abort, fixed in §2); waiting on the re-run.
- [ ] Tag `v0.1.0`, confirm the JitPack build is green, verify the install snippet in the README.
- [ ] (Optional) Maven Central: add `<url>`, `<scm>`, `<developers>` to the pom;
      `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
      `central-publishing-maven-plugin`; verify the namespace in the Central Portal; make sure
      `pool-warden-demo` and `pool-warden-benchmark` stay unpublished.

### Milestone 4 — Post-release (1.x)
- [ ] **Spring Boot 4.** Boot 4 splits the auto-configuration modules (e.g. the
      `DataSourceAutoConfiguration` package moves). Decide: 0.x for Boot 3.5 and 1.x for Boot 4 —
      or support both through a CI matrix.
- [ ] **Stack-trace sampling** (e.g. capture 1 in N checkouts). The benchmark shows call-site
      capture costs ~10 µs per checkout versus ~175 ns for a HikariCP checkout itself; sampling is
      the middle ground for high throughput, and it is the top priority of this milestone.
- [ ] Active health checker for stale/zombie connections (the original motivation of the brief, a
      mechanism separate from the tracking map).
- [ ] "Top offender call-site" aggregation (bounded per-call-site counters).
- [ ] Sample Grafana dashboard + `docker compose` (app + Postgres + Prometheus + Grafana).
- [ ] P2.4: the endpoint security note is already in the README; consider a property that hides
      call-sites for locked-down environments.

## 4. Definition of done for `v0.1.0`

- [x] REAP is proven to recover pool capacity on HikariCP, Tomcat JDBC and DBCP2 (automated test).
- [ ] Testcontainers PostgreSQL proves the backend disappears from `pg_stat_activity` (needs a green CI run).
- [x] The README carries the JMH numbers and makes no untested claims.
- [x] GitHub repo exists and CI runs on every push to `main`.
- [ ] CI green on JDK 17 and 21.
- [ ] The artifact resolves from JitPack with the snippet in the README.
