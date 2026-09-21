# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-22

First release.

### Added

- `WardenDataSource` and `WardenConnectionHandler`: pool-agnostic interception of
  `getConnection()` and `close()` on top of plain `javax.sql.DataSource`.
- `ConnectionTracker` with per-checkout entries, including the thread and the application
  call-site that borrowed the connection.
- `PoolSweeper` with two modes: `WARN_ONLY` (log and count) and `REAP` (force-reclaim past the
  kill threshold), with a compare-and-set that settles a close racing the sweeper.
- Spring Boot starter: auto-configuration, `pool-warden.*` properties, automatic wrapping of
  every pooled `DataSource` bean.
- Micrometer meters and the read-only `/actuator/poolwarden` endpoint, both tagged with the
  data source bean name so applications with several pools stay legible.
- Integration tests that fill a real pool, leak it and assert capacity comes back, across
  HikariCP, Tomcat JDBC and Commons DBCP2, plus a Testcontainers PostgreSQL test that checks
  the backend is gone from `pg_stat_activity`.
- JMH benchmarks for the checkout path.

[Unreleased]: https://github.com/rizkywahyus/spring-pool-warden/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/rizkywahyus/spring-pool-warden/releases/tag/v0.1.0
