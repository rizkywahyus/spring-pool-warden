# pool-warden — Roadmap menuju rilis

> Snapshot: 2026-09-20. Versi `0.1.0-SNAPSHOT`, siap di-tag `v0.1.0` setelah repo dipush.

## 1. Status sekarang

| Area | Status | Catatan |
|---|---|---|
| Proxy `WardenDataSource` + `WardenConnectionHandler` | ✅ | Hanya `Connection` yang di-proxy, `Statement`/`ResultSet` tidak |
| `ConnectionTracker` + `TrackedEntry` | ✅ | Key pakai id; entry membawa nama datasource |
| `PoolSweeper` mode `WARN_ONLY` | ✅ | Warn sekali per leak |
| Mode `REAP` + CAS untuk race close vs reap | ✅ | **Kapasitas pool benar-benar pulih** — lihat §2 |
| Call-site capture via `StackWalker` | ✅ | Filter frame Spring AOP/TX/proxy; bisa dimatikan |
| Spring Boot starter (auto-config, properties, BPP) | ✅ | Boot 3.5.16, baseline Java 17 |
| Micrometer metrics | ✅ | 4 meter, semua bertag `datasource` |
| Actuator endpoint `/actuator/poolwarden` | ✅ | Read-only, menampilkan datasource per koneksi |
| Multi-datasource | ✅ | Per-bean tag + skip `DelegatingDataSource`/routing |
| Demo app (H2 + Hikari) | ✅ | |
| README + trade-off jujur | ✅ | Termasuk angka JMH |
| Test | ✅ 49 test | `./mvnw clean verify` hijau; coverage core 86%, starter 93% |
| Matrix HikariCP / Tomcat JDBC / DBCP2 | ✅ | Test kapasitas pulih untuk ketiganya |
| Testcontainers PostgreSQL | ✅ | Skip otomatis tanpa Docker, jalan di CI |
| JMH benchmark | ✅ | Modul `pool-warden-benchmark` |
| LICENSE + CI (JDK 17 & 21) + jitpack.yml + CHANGELOG | ✅ | |
| Git repo / push / tag rilis | ⬜ | Repo lokal sudah `git init`; remote GitHub belum dibuat |
| Spring Boot 4 | ⬜ | Lihat §3 Milestone 4 |

## 2. P0 yang sudah dituntaskan

**REAP tidak membebaskan slot pool.** Dulu `ConnectionReaper` hanya memanggil `abort()`. Koneksi
fisik mati, tapi pool tetap menghitung slot itu `IN_USE` sampai `close()` dipanggil pada koneksi
yang *pool* berikan — dan pada leak sungguhan aplikasi tidak pernah memanggilnya. Hasilnya: leak
mati, pool tetap habis.

Fix yang dipakai (opsi A, pool-agnostic): setelah `abort()`, reaper memanggil `close()` pada
koneksi level-pool. Pool menerima slotnya kembali lalu membuang koneksi mati itu lewat validasi
sendiri, jadi `close()` yang gagal setelah abort adalah hasil normal, bukan error —
di-log pada level debug. Konsekuensinya `WardenConnectionHandler.close()` tidak lagi meneruskan
`close()` kalau entry sudah di-reap, supaya pool tidak menerima release ganda.

Bukti: `PoolCapacityRecoveryTest` (HikariCP, Tomcat JDBC, DBCP2) dan `PostgresReapTest`
(PostgreSQL 16 lewat Testcontainers) — keduanya meng-assert `getConnection()` berikutnya berhasil,
bukan sekadar `abort()` terpanggil.

Temuan lain yang ikut selesai: P1.3 double-wrapping, P1.4 tag `datasource`, P1.5 baseline Java 17,
P2.1 urutan shutdown sweeper, P2.2 nama virtual thread, P2.3 filter frame framework,
P2.5 `ageMillis` yang terabaikan.

## 3. Sisa pekerjaan

### Milestone 3 — Rilis `v0.1.0`
- [ ] Buat repo GitHub `rizkywahyus/spring-pool-warden` (username harus cocok dengan
      `groupId io.github.rizkywahyus` kalau nanti ke Maven Central), push `main`.
- [ ] Cek CI hijau di JDK 17 dan 21, termasuk test Testcontainers PostgreSQL.
- [ ] Tag `v0.1.0`, pastikan build JitPack hijau, verifikasi snippet install di README.
- [ ] (Opsional) Maven Central: tambah `<url>`, `<scm>`, `<developers>` di pom;
      `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`,
      `central-publishing-maven-plugin`; verifikasi namespace di Central Portal; pastikan
      `pool-warden-demo` dan `pool-warden-benchmark` tetap tidak ter-publish.

### Milestone 4 — Pasca rilis (1.x)
- [ ] **Spring Boot 4.** Boot 4 memecah modul auto-config (mis. package `DataSourceAutoConfiguration`
      pindah). Putuskan: 0.x untuk Boot 3.5, 1.x untuk Boot 4 — atau dukung keduanya lewat CI matrix.
- [ ] **Sampling stack trace** (mis. capture 1 dari N checkout). Benchmark menunjukkan capture
      call-site ~10 µs per checkout versus ~175 ns untuk checkout HikariCP sendiri; sampling adalah
      jalan tengah untuk throughput tinggi, dan ini prioritas tertinggi di milestone ini.
- [ ] Active health checker untuk stale/zombie connection (motivasi awal brief, mekanisme terpisah
      dari tracking-map).
- [ ] Agregasi "top offender call-site" (counter per call-site, bounded).
- [ ] Contoh dashboard Grafana + `docker compose` (app + Postgres + Prometheus + Grafana).
- [ ] P2.4: catatan security endpoint sudah masuk README; pertimbangkan opsi menyembunyikan
      call-site lewat property untuk lingkungan yang ketat.

## 4. Definition of done untuk `v0.1.0`

- [x] REAP terbukti memulihkan kapasitas pool di HikariCP, Tomcat JDBC, dan DBCP2 (test otomatis).
- [x] Testcontainers PostgreSQL membuktikan backend hilang dari `pg_stat_activity`.
- [x] README memuat angka JMH dan tidak ada klaim yang belum dites.
- [ ] CI hijau di JDK 17 dan 21 (butuh repo GitHub).
- [ ] Artifact bisa di-resolve dari JitPack dengan snippet di README.
