# pool-warden

[![build](https://github.com/rizkywahyus/spring-pool-warden/actions/workflows/build.yml/badge.svg)](https://github.com/rizkywahyus/spring-pool-warden/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/rizkywahyus/spring-pool-warden.svg)](https://jitpack.io/#rizkywahyus/spring-pool-warden)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Finds leaked JDBC connections while they are still leaking — and, if you let it, takes them back.

`leakDetectionThreshold` in HikariCP only logs. `removeAbandoned` in Tomcat JDBC / DBCP2 actually
reclaims, but those pools are rarely used in new projects. `pool-warden` puts both behaviours behind
one Spring Boot starter that works on **any** `javax.sql.DataSource`.

```
app.getConnection()
        │
        ▼
┌───────────────────┐   checkout   ┌────────────────────┐
│ WardenDataSource  │ ───────────► │ ConnectionTracker  │
│ (wraps your pool) │              │ id → thread, age,  │
└───────┬───────────┘ ◄─────────── │ call-site          │
        │              close()     └─────────┬──────────┘
        ▼                                    │ every sweep-interval
┌───────────────────┐              ┌─────────▼──────────┐
│ HikariCP / Tomcat │              │   PoolSweeper      │
│ / DBCP2 / custom  │              ├────────────────────┤
└───────────────────┘              │ age > warn  → WARN │
        ▲                          │ age > kill  → REAP │
        └──────── Connection.abort()┤ (opt-in)           │
                                   └────────────────────┘
```

## Install

Java 17+ and Spring Boot 3.5.x. Released through [JitPack](https://jitpack.io/#rizkywahyus/spring-pool-warden):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.rizkywahyus.spring-pool-warden</groupId>
  <artifactId>pool-warden-spring-boot-starter</artifactId>
  <version>v0.1.0</version>
</dependency>
```

```groovy
repositories { maven { url 'https://jitpack.io' } }

implementation 'com.github.rizkywahyus.spring-pool-warden:pool-warden-spring-boot-starter:v0.1.0'
```

No code changes: the starter wraps every pooled `DataSource` bean automatically. Data sources
that only forward to another one (`DelegatingDataSource`, `LazyConnectionDataSourceProxy`,
`AbstractRoutingDataSource`) are left alone, so a checkout is never counted twice.

## Configure

```yaml
pool-warden:
  enabled: true
  mode: WARN_ONLY        # WARN_ONLY (default) or REAP
  warn-threshold: 30s    # report a checkout older than this
  kill-threshold: 120s   # force-reclaim after this (REAP only, must be > warn-threshold)
  sweep-interval: 10s
  capture-stack-trace: true   # off = cheaper checkouts, leak reports without a call-site
```

A leak report names the thread, the age and the exact line that took the connection:

```
WARN  i.g.r.p.core.sweeper.PoolSweeper : Connection from 'dataSource' held for 6961 ms
      (threshold 5000 ms) by thread 'http-nio-8080-exec-3'. Not closed yet -- likely a leak.
      Checked out at:
	at com.acme.billing.InvoiceController.export(InvoiceController.java:33)
	at ...
```

`'dataSource'` is the bean name of the pool the connection came from, so a multi-pool application
says which one is leaking.

## Observability

With Micrometer on the classpath the warden registers:

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `pool.warden.connections.active` | gauge | `datasource` | connections currently checked out |
| `pool.warden.leaks.warned` | counter | `datasource` | leaks reported (once per leak) |
| `pool.warden.leaks.reaped` | counter | `datasource` | leaks force-reclaimed |
| `pool.warden.leaks.age` | timer | `datasource`, `event` | how long a leaked connection had been held when it was `warned` or `reaped` |

`datasource` is the bean name of the pool, so an application with several pools can tell them
apart. All meters are registered at startup, so a dashboard or alert can be built before the
first leak happens.

With Actuator, `GET /actuator/poolwarden` lists what is checked out right now, oldest first, with
the data source, thread and call-site of each — useful while a pool is filling up rather than
afterwards in logs.

> **Security:** the endpoint exposes thread names and application call-sites. It is not exposed by
> default; keep it off public listeners, or behind the same authentication as the rest of
> `/actuator`.

## Run the demo in 5 minutes

```bash
./mvnw -q -DskipTests package
java -jar pool-warden-demo/target/pool-warden-demo-0.1.0-SNAPSHOT.jar

curl localhost:8080/normal   # borrows and returns a connection properly
curl localhost:8080/leak     # borrows one and never closes it
                             # ~5s later a WARN with the call-site appears in the log
curl localhost:8080/actuator/poolwarden
curl -s localhost:8080/actuator/prometheus | grep pool_warden
curl localhost:8080/release  # hand the leaked connections back
```

Watch reaping instead:

```bash
java -jar pool-warden-demo/target/pool-warden-demo-0.1.0-SNAPSHOT.jar \
  --pool-warden.mode=REAP --pool-warden.warn-threshold=3s --pool-warden.kill-threshold=6s
curl localhost:8080/leak
# WARN ... Reclaimed connection held for 7454 ms (kill threshold 6000 ms) ...
```

## Design decisions & trade-offs

**Honest limits — read these before turning on `REAP`.**

- **Force-close is not safe by definition.** A connection past the kill threshold may be running a
  slow but perfectly valid transaction. Aborting it rolls that work back and can leave data the
  application expected to be written missing. `REAP` is opt-in and `WARN_ONLY` is the default for
  this reason.
- **`abort()` releases the client side, not necessarily the database side.** The server may hold
  locks or the transaction until its own timeout fires.
- **It is a safety net, not a fix.** If reaping is quietly cleaning up after the same call-site
  every day, the bug is still in the code. The call-site in the leak report is there to be acted on.
- **Close racing the sweeper** is resolved with a compare-and-set per checkout: either the
  application's `close()` or the sweeper's reap claims the entry, never both. See `TrackedEntry`.
- **Stack trace capture is the expensive part — measure before leaving it on.** It uses
  `StackWalker` with a frame limit instead of `Throwable.getStackTrace()`, but it still costs
  roughly **10 µs per checkout** (see the benchmark below), against ~175 ns for a HikariCP
  checkout itself. That is fine for a service doing millisecond-scale queries and far too much for
  a hot in-memory path; set `capture-stack-trace: false` there and leaks are reported with the
  data source, thread and age only.
- **The JDK dynamic proxy adds one reflective hop per `Connection` call.** Statements and result
  sets are deliberately not wrapped, so the overhead stays on the connection lifecycle. Measured
  at **~150 ns per checkout** — see the benchmark numbers below.
- **Reaping gives the slot back by closing after aborting.** `abort()` kills the physical
  connection, but a pool only counts a slot as free once `close()` is called on the connection it
  handed out — and a leaking application never makes that call. The reaper therefore does both.
  The pool then discards the dead connection on its own validation, which is why `close()` failing
  after an abort is expected rather than an error.
- **Leaks only, not stale connections.** A connection killed by a load balancer or firewall still
  looks healthy in the tracking map. Detecting that needs active validation, which is a separate
  mechanism (roadmap).
- **Only what goes through a wrapped `DataSource`.** A data source that is not a Spring bean, or a
  driver connection obtained directly through `DriverManager`, is invisible to the warden. The same
  applies to a pool that only exists behind a `DelegatingDataSource` bean and is not a bean itself:
  nothing is wrapped in that case.
- **Per-instance, not cluster-wide.** The tracking map lives in one JVM, while `max_connections`
  usually lives on the database. Aggregate the metrics if you run many pods.
- **Your `DataSource` bean becomes a `WardenDataSource`.** Code that injects a concrete pool type
  (`HikariDataSource`) instead of the `DataSource` interface will not find that bean. Use
  `dataSource.unwrap(HikariDataSource.class)`, which the wrapper supports.

## Testing

```bash
./mvnw clean verify
```

49 tests — 86% instruction coverage in `pool-warden-core`, 93% in the starter:

- **Pool matrix.** Fill a pool of 2, leak every connection, wait for the reap, then check the next
  `getConnection()` succeeds — run against **HikariCP, Tomcat JDBC and Commons DBCP2**. This is the
  test that proves reaping gives capacity back rather than only killing the connection.
- **Real PostgreSQL** via Testcontainers: after a reap the backends are gone from
  `pg_stat_activity` and the pool serves again. Skipped automatically when Docker is unavailable,
  and always run in CI.
- Connection interception, tracker concurrency (8 threads × 2000 checkouts), sweeper thresholds,
  the reap/close race over 2000 rounds, auto-configuration conditions, per-data-source metrics
  tags, multi-pool attribution, delegating-data-source skipping, and the actuator endpoint.

CI runs the whole build on **JDK 17 and 21**.

## Benchmarks

`pool-warden-benchmark` is a JMH harness for the checkout path — one `getConnection()` plus the
matching `close()`. Numbers below are from a 2.6 GHz x86-64 macOS laptop on JDK 21; run your own
with:

```bash
./mvnw -q -DskipTests package
java -jar pool-warden-benchmark/target/benchmarks.jar
```

| Benchmark | ns/op | What it says |
|---|---:|---|
| `stubBaseline` | 0.7 | a do-nothing data source, for reference |
| `stubThroughWarden` | 158 | the warden's own cost: proxy + tracker, no call-site |
| `stubThroughWardenCapturingCallSite` | 9 864 | the same with `capture-stack-trace: true` |
| `hikariBaseline` | 174 | HikariCP + H2, unwrapped |
| `hikariThroughWarden` | 325 | the same pool behind the warden |

Reading it: tracking costs about as much again as a HikariCP checkout, which is negligible next to
any real query. Capturing the call-site costs roughly 60× that, which is the one setting worth
thinking about before enabling it on a hot path.

## Roadmap

See [ROADMAP.md](ROADMAP.md). Next up: Spring Boot 4 support, active health checking for
stale/zombie connections, call-site aggregation and stack-trace sampling.

## License

Apache 2.0.
