# Verifying `Connection.abort()` against a real PostgreSQL

**This is now automated.** `PostgresReapTest` in `pool-warden-core` starts PostgreSQL 16 with
Testcontainers, leaks a full pool, waits for the reap and asserts both that the backends are gone
from `pg_stat_activity` and that the pool serves connections again. It skips itself when Docker is
not available and always runs in CI.

```bash
./mvnw -pl pool-warden-core -Dtest=PostgresReapTest test
```

What follows is the manual version, kept for poking at a running system by hand — watching locks,
trying another PostgreSQL version, or checking a different driver.

## 1. Start PostgreSQL

```bash
docker run --rm -d --name pw-pg \
  -e POSTGRES_PASSWORD=warden -e POSTGRES_DB=warden \
  -p 5432:5432 postgres:16
```

## 2. Point the demo at it

```bash
java -jar pool-warden-demo/target/pool-warden-demo-0.1.0-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/warden \
  --spring.datasource.username=postgres \
  --spring.datasource.password=warden \
  --spring.datasource.driver-class-name=org.postgresql.Driver \
  --pool-warden.mode=REAP \
  --pool-warden.warn-threshold=3s \
  --pool-warden.kill-threshold=6s
```

The demo module needs the PostgreSQL driver on its classpath for this; add
`org.postgresql:postgresql` to `pool-warden-demo/pom.xml` before packaging.

## 3. Leak a connection and watch the server side

```bash
curl localhost:8080/leak

# Before the kill threshold: the backend is there.
docker exec pw-pg psql -U postgres -d warden \
  -c "select pid, state, query_start from pg_stat_activity where datname='warden';"

# After the kill threshold (~6s), the log shows "Reclaimed connection held for ..." and the
# backend for that session should be gone from the same query.
```

## 4. What to record

- Whether the backend disappears from `pg_stat_activity` immediately or after a delay.
- Whether any lock held by that session is released at the same time (`pg_locks`) — this is the
  part most likely to lag, and it is what the README's "releases the client side, not necessarily
  the database side" caveat is about.

## 5. Clean up

```bash
docker rm -f pw-pg
```
