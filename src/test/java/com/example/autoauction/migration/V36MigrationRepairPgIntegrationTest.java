package com.example.autoauction.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration-repair verification (Step 1G) for the pre-existing historical defect in
 * {@code V36__auction_commitment_linkage.sql}: its first statement was a plain
 * {@code ALTER TABLE vehicles ADD COLUMN diagnostic_request_id ...}, but
 * {@code V9__diagnostic_system.sql} already adds that exact column
 * ({@code ADD COLUMN IF NOT EXISTS diagnostic_request_id BIGINT REFERENCES diagnostic_requests(id)}).
 * On a genuinely clean PostgreSQL database V9 creates the column, so V36 failed with
 * {@code SQLSTATE 42701 - column "diagnostic_request_id" of relation "vehicles" already exists},
 * blocking the whole V1&rarr;V42 chain (the repository's {@code docker-compose} deploy path).
 *
 * <p>The authorized repair adds {@code IF NOT EXISTS} to that one statement only. On PostgreSQL,
 * {@code ADD COLUMN IF NOT EXISTS col ... REFERENCES ...} skips the entire clause (no second
 * column, no second FK, no error) when the column already exists, and still adds column + FK when
 * it is absent. V36's other three statements (the {@code vehicles} UNIQUE, the {@code auctions}
 * column + FK, the {@code auctions} UNIQUE) are unchanged and still run.
 *
 * <p>Runs against real <b>PostgreSQL 15</b> (the version in the repository's
 * {@code docker-compose.yml}), driving Flyway directly through its Java API against an empty
 * database - no Spring context, no {@code DataInitializer}, no dev profile, no manually inserted
 * users, no baseline manipulation.
 *
 * <p>Docker Engine 29.x requires API &gt;= 1.44; supplied by
 * {@code src/test/resources/docker-java.properties} (api.version=1.44) and/or
 * {@code ~/.docker-java.properties}.
 */
@Testcontainers
@DisplayName("Migration repair · V36 · PostgreSQL 15")
class V36MigrationRepairPgIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final String LOC = "classpath:db/migration";
    private static final String SEDAN = "Стандартный шаблон для седана";

    private void wipe() throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private long scalar(String sql) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String text(String sql) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private Flyway flyway(String location, String target) {
        var cfg = Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations(location);
        if (target != null) cfg = cfg.target(target);
        return cfg.load();
    }

    private MigrationInfo appliedVersion(Flyway fw, String version) {
        return Stream.of(fw.info().applied())
                .filter(m -> m.getVersion() != null && version.equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow(() -> new AssertionError("migration " + version + " not applied"));
    }

    // ── 1. PRIMARY GATE: clean PostgreSQL 15, full V1 → V42 ────────────────────────────────────

    @Test
    @DisplayName("clean empty PostgreSQL 15 applies the full V1→V42 chain; V42 current; no pending; every migration SUCCESS")
    void cleanChainReachesV42() throws Exception {
        wipe();
        Flyway fw = flyway(LOC, null); // no target -> full chain
        fw.migrate();

        // ── Flyway-level ──────────────────────────────────────────────────────────────────────
        MigrationInfo current = fw.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion()).isNotNull();
        // TEST-02: was pinned to "42" and broke with every later migration; the chain must reach the newest one
        assertThat(current.getVersion().getVersion()).as("latest applied migration is the newest available")
                .isEqualTo(latestAvailable(fw));
        assertThat(Integer.parseInt(latestAvailable(fw))).isGreaterThanOrEqualTo(42);
        assertThat(fw.info().pending()).as("no pending migrations after full migrate").isEmpty();
        for (MigrationInfo mi : fw.info().applied()) {
            assertThat(mi.getState()).as("migration %s state", mi.getVersion())
                    .isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
        }

        // ── V10 (Step 1E correction preserved) ────────────────────────────────────────────────
        assertThat(appliedVersion(fw, "10").getState()).isEqualTo(MigrationState.SUCCESS);
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT created_by FROM inspection_templates WHERE name = '" + SEDAN + "'")) {
            assertThat(rs.next()).as("sedan template row exists").isTrue();
            rs.getObject("created_by");
            assertThat(rs.wasNull()).as("V10: inspection_templates.created_by IS NULL for the seeded template").isTrue();
            assertThat(rs.next()).as("exactly one sedan template row").isFalse();
        }

        // ── §8 no synthetic user: the chain itself created none ───────────────────────────────
        assertThat(scalar("SELECT COUNT(*) FROM users")).as("migration chain seeded no users").isZero();

        // ── V36: intended resulting schema ───────────────────────────────────────────────────
        assertThat(appliedVersion(fw, "36").getState()).isEqualTo(MigrationState.SUCCESS);

        // vehicles.diagnostic_request_id : bigint, nullable
        assertThat(text("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'"))
                .as("vehicles.diagnostic_request_id type").isEqualTo("bigint");
        assertThat(text("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'"))
                .as("vehicles.diagnostic_request_id nullable").isEqualTo("YES");
        // exactly one FK vehicles.diagnostic_request_id -> diagnostic_requests (no duplicate from V9 + V36)
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'vehicles'::regclass "
                + "AND confrelid = 'diagnostic_requests'::regclass"))
                .as("exactly one vehicles->diagnostic_requests FK (no duplicate)").isEqualTo(1);
        // UNIQUE uk_vehicles_diagnostic_request_id (V36 stmt 2)
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname = 'uk_vehicles_diagnostic_request_id' AND contype = 'u' "
                + "AND conrelid = 'vehicles'::regclass"))
                .as("uk_vehicles_diagnostic_request_id UNIQUE present").isEqualTo(1);

        // auctions.seller_diagnostic_commitment_id : bigint, nullable, FK, UNIQUE (V36 stmt 3+4)
        assertThat(text("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'auctions' AND column_name = 'seller_diagnostic_commitment_id'"))
                .as("auctions.seller_diagnostic_commitment_id type").isEqualTo("bigint");
        assertThat(text("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'auctions' AND column_name = 'seller_diagnostic_commitment_id'"))
                .as("auctions.seller_diagnostic_commitment_id nullable").isEqualTo("YES");
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'auctions'::regclass "
                + "AND confrelid = 'seller_diagnostic_commitment'::regclass"))
                .as("exactly one auctions->seller_diagnostic_commitment FK").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname = 'uk_auctions_seller_diagnostic_commitment_id' AND contype = 'u' "
                + "AND conrelid = 'auctions'::regclass"))
                .as("uk_auctions_seller_diagnostic_commitment_id UNIQUE present").isEqualTo(1);

        // ── §6 V37 → V42 recorded successful, plus a representative object from each ───────────
        for (String v : new String[] {"37", "38", "39", "40", "41", "42"}) {
            assertThat(appliedVersion(fw, v).getState()).as("V%s SUCCESS", v).isEqualTo(MigrationState.SUCCESS);
        }
        // representative schema objects created by V37..V42
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'violation_definition'"))
                .as("V37 created violation_definition").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'policy_version_violation'"))
                .as("V38 created policy_version_violation").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'seller_diagnostic_commitment' AND column_name = 'policy_version_id'"))
                .as("V39 added seller_diagnostic_commitment.policy_version_id").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'seller_violation_resolution'"))
                .as("V40 created seller_violation_resolution").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'payment'"))
                .as("V42 created payment").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = 'reconciliation_case'"))
                .as("V42 created reconciliation_case").isEqualTo(1);
    }

    // ── 2. §7 IF NOT EXISTS semantics: V9 creates the column, V36 no-ops it, keeps its own DDL ─

    @Test
    @DisplayName("V9 creates vehicles.diagnostic_request_id; repaired V36 does not fail on it and still creates its own constraints")
    void v36NoOpsExistingColumnButStillAddsItsConstraints() throws Exception {
        wipe();
        // up to V9 only: the column + its FK exist, no UNIQUE yet
        flyway(LOC, "9").migrate();
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'"))
                .as("V9 created vehicles.diagnostic_request_id").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'vehicles'::regclass "
                + "AND confrelid = 'diagnostic_requests'::regclass"))
                .as("V9 created the FK").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname = 'uk_vehicles_diagnostic_request_id'"))
                .as("no UNIQUE before V36").isZero();

        // now run through V36
        Flyway fw = flyway(LOC, "36");
        fw.migrate();
        assertThat(appliedVersion(fw, "36").getState()).as("repaired V36 applied on top of V9's column")
                .isEqualTo(MigrationState.SUCCESS);

        // still exactly one column, still exactly one FK (no duplicate), FK still targets diagnostic_requests
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'vehicles'::regclass "
                + "AND confrelid = 'diagnostic_requests'::regclass"))
                .as("V36 did NOT add a second FK").isEqualTo(1);
        // V36 still added its own three objects
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uk_vehicles_diagnostic_request_id'"))
                .as("V36 stmt 2 ran").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'auctions' AND column_name = 'seller_diagnostic_commitment_id'"))
                .as("V36 stmt 3 ran").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uk_auctions_seller_diagnostic_commitment_id'"))
                .as("V36 stmt 4 ran").isEqualTo(1);
    }

    // ── 3. §9 existing DB with OLD V36 checksum → validate fails → repair → migrate → data safe ─

    /** Copies the live migration dir and reverts V36's one authorized token (IF NOT EXISTS → plain). */
    private Path oldV36MigrationSet(Path dir) throws Exception {
        Path src = Path.of("src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(src)) {
            files.forEach(p -> {
                try { Files.copy(p, dir.resolve(p.getFileName())); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        Path v36 = dir.resolve("V36__auction_commitment_linkage.sql");
        String repaired = Files.readString(v36);
        String original = repaired.replace(
                "ADD COLUMN IF NOT EXISTS diagnostic_request_id BIGINT NULL",
                "ADD COLUMN diagnostic_request_id BIGINT NULL");
        assertThat(original).as("inverse of the one-token V36 repair applied").isNotEqualTo(repaired);
        Files.writeString(v36, original);
        return dir;
    }

    @Test
    @DisplayName("A) old V36 applied → B) repaired V36 fails validation, mutates nothing → C) repair → D) migrate ok → E) schema present once, metadata-only")
    void oldChecksumThenRepair(@TempDir Path tempDir) throws Exception {
        wipe();
        String oldLoc = "filesystem:" + oldV36MigrationSet(tempDir).toAbsolutePath();

        // Stage A: apply V1..V35 (identical to the live set), then model the state V36 was authored
        // against - vehicles.diagnostic_request_id absent when V36 runs - WITHOUT touching V9's
        // file or checksum, then let the OLD (plain ADD COLUMN) V36 apply and finish the chain.
        flyway(oldLoc, "35").migrate();
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE vehicles DROP COLUMN diagnostic_request_id");
        }
        flyway(oldLoc, null).migrate(); // OLD V36 + V37..V42

        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'"))
                .as("old V36 re-created the column").isEqualTo(1);
        long oldChecksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version = '36'");
        long historyRowsBefore = scalar("SELECT COUNT(*) FROM flyway_schema_history");
        long v36RankBefore = scalar("SELECT installed_rank FROM flyway_schema_history WHERE version = '36'");
        long v36InstalledOnBefore = scalar(
                "SELECT EXTRACT(EPOCH FROM installed_on)::bigint FROM flyway_schema_history WHERE version = '36'");

        // Stage B: present the live (repaired) migration set -> validate & migrate must fail on the
        // V36 checksum and change nothing.
        Flyway repaired = flyway(LOC, null);
        assertThatThrownBy(repaired::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum mismatch")
                .hasMessageContaining("36");
        assertThatThrownBy(repaired::migrate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("36");
        assertThat(scalar("SELECT checksum FROM flyway_schema_history WHERE version = '36'"))
                .as("stored checksum still the old one after failed validate/migrate").isEqualTo(oldChecksum);

        // Stage C: repair (disposable DB only).
        repaired.repair();

        // Stage D: migrate again -> validation passes, nothing pending.
        repaired.migrate();
        assertThat(repaired.info().pending()).isEmpty();

        // Stage E: repair rewrote metadata only - it re-ran no migration SQL.
        assertThat(scalar("SELECT COUNT(*) FROM flyway_schema_history"))
                .as("repair+migrate added no history rows").isEqualTo(historyRowsBefore);
        assertThat(scalar("SELECT installed_rank FROM flyway_schema_history WHERE version = '36'"))
                .as("V36 installed_rank unchanged").isEqualTo(v36RankBefore);
        assertThat(scalar("SELECT EXTRACT(EPOCH FROM installed_on)::bigint FROM flyway_schema_history WHERE version = '36'"))
                .as("V36 installed_on unchanged").isEqualTo(v36InstalledOnBefore);

        // schema objects present exactly once (no re-execution, no duplication)
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'vehicles' AND column_name = 'diagnostic_request_id'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE contype = 'f' AND conrelid = 'vehicles'::regclass "
                + "AND confrelid = 'diagnostic_requests'::regclass")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint WHERE conname = 'uk_vehicles_diagnostic_request_id'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'auctions' AND column_name = 'seller_diagnostic_commitment_id'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM pg_constraint "
                + "WHERE conname = 'uk_auctions_seller_diagnostic_commitment_id'")).isEqualTo(1);

        long newChecksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version = '36'");
        assertThat(newChecksum).as("stored checksum updated by repair").isNotEqualTo(oldChecksum);
        int repairedFileChecksum = Stream.of(flyway(LOC, null).info().all())
                .filter(m -> m.getVersion() != null && "36".equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow().getChecksum();
        assertThat(newChecksum).as("stored checksum equals the repaired V36 file's Flyway checksum")
                .isEqualTo((long) repairedFileChecksum);
    }

    /** TEST-02: the newest migration on the classpath - the chain must always reach it (never pinned to a number). */
    private static String latestAvailable(Flyway fw) {
        return java.util.Arrays.stream(fw.info().all())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder())
                .orElseThrow()
                .getVersion();
    }
}
