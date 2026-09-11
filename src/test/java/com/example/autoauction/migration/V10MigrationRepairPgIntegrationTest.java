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
 * Migration-repair verification (Step 1E) for the pre-existing historical defect in
 * {@code V10__init_diagnostic_templates.sql}: it seeded {@code inspection_templates.created_by = 1}
 * (a FK to {@code users(id)}) although no migration seeds a {@code users} row and
 * {@code DataInitializer} is {@code @Profile("dev")} and runs after Flyway - so a genuinely empty
 * database failed at V10. The repair changes the seed value to {@code NULL} (the column is nullable
 * per V9).
 *
 * <p>Runs against real <b>PostgreSQL 15</b> (the version in the repository's {@code docker-compose.yml}),
 * driving Flyway directly through its Java API against an empty database - no Spring context, no
 * {@code DataInitializer}, no dev profile, no manually inserted users, no baseline manipulation.
 *
 * <p><b>Scope note:</b> with V10 repaired, the clean chain advanced 26 migrations further and hit a
 * <i>separate</i> pre-existing defect in {@code V36__auction_commitment_linkage.sql} (plain
 * {@code ALTER TABLE vehicles ADD COLUMN diagnostic_request_id} - V9 already added that column
 * {@code IF NOT EXISTS}). That defect was fixed under its own authorization in Step 1G (V36 line 38
 * gained {@code IF NOT EXISTS}); the full clean V1&rarr;V42 chain is verified by
 * {@link V36MigrationRepairPgIntegrationTest}. The V10-specific proofs below still run against a
 * partial chain up to V35 so they stay independent of later migrations; one test confirms the chain
 * now advances cleanly past V10 through V36 to V42.
 *
 * <p>Docker Engine 29.x requires API >= 1.44; supplied by {@code src/test/resources/docker-java.properties}
 * (api.version=1.44) and/or {@code ~/.docker-java.properties}.
 */
@Testcontainers
@DisplayName("Migration repair · V10 · PostgreSQL 15")
class V10MigrationRepairPgIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final String LOC = "classpath:db/migration";
    private static final String SEDAN = "Стандартный шаблон для седана";
    /** Last version before the separate, pre-existing V36 defect. */
    private static final String TARGET_BEFORE_V36 = "35";

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

    private Flyway flyway(String location, String target) {
        var cfg = Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations(location);
        if (target != null) cfg = cfg.target(target);
        return cfg.load();
    }

    // ── 1. The V10 repair: clean chain reaches and applies V10 with created_by = NULL ────────

    @Test
    @DisplayName("repaired V10 applies on empty PostgreSQL 15 (V1→V35); V10 SUCCESS; created_by IS NULL; no user seeded; no duplicate")
    void repairedV10AppliesCleanly() throws Exception {
        wipe();
        Flyway fw = flyway(LOC, TARGET_BEFORE_V36);
        fw.migrate();

        MigrationInfo[] applied = fw.info().applied();
        MigrationInfo v10 = Stream.of(applied)
                .filter(m -> m.getVersion() != null && "10".equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow();
        assertThat(v10.getState()).as("V10 applied successfully on a clean DB").isEqualTo(MigrationState.SUCCESS);
        for (MigrationInfo mi : applied) {
            assertThat(mi.getState()).as("migration %s", mi.getVersion())
                    .isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
        }

        // sedan template exists and created_by IS NULL
        try (Connection c = conn(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT created_by FROM inspection_templates WHERE name = '" + SEDAN + "'")) {
            assertThat(rs.next()).as("sedan template row exists").isTrue();
            rs.getObject("created_by");
            assertThat(rs.wasNull()).as("inspection_templates.created_by IS NULL for the seeded template").isTrue();
            assertThat(rs.next()).as("exactly one sedan template row").isFalse();
        }
        // no synthetic user
        assertThat(scalar("SELECT COUNT(*) FROM users")).as("no user was inserted to satisfy V10").isZero();
        // V10's child seeds landed
        assertThat(scalar("SELECT COUNT(*) FROM inspection_groups")).isGreaterThan(0);
        assertThat(scalar("SELECT COUNT(*) FROM inspection_items")).isGreaterThan(0);
    }

    // ── 2. The clean chain now advances past V10 through the (Step 1G) repaired V36 to V42 ───

    @Test
    @DisplayName("full V1→V42 completes on empty PostgreSQL 15; V10 and V36 both SUCCESS (no V10 regression, V36 defect fixed in Step 1G)")
    void fullChainReachesV42() throws Exception {
        wipe();
        Flyway fw = flyway(LOC, null); // no target -> full chain
        fw.migrate();

        MigrationInfo[] applied = fw.info().applied();
        for (MigrationInfo mi : applied) {
            assertThat(mi.getState()).as("migration %s", mi.getVersion())
                    .isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
        }
        MigrationInfo v10 = Stream.of(applied)
                .filter(m -> m.getVersion() != null && "10".equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow();
        assertThat(v10.getState()).as("V10 applied successfully").isEqualTo(MigrationState.SUCCESS);
        MigrationInfo v36 = Stream.of(applied)
                .filter(m -> m.getVersion() != null && "36".equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow();
        assertThat(v36.getState()).as("V36 applied successfully (IF NOT EXISTS repair)")
                .isEqualTo(MigrationState.SUCCESS);
        assertThat(fw.info().current().getVersion().getVersion()).as("chain reaches V42").isEqualTo("42");
        assertThat(fw.info().pending()).as("no pending migrations").isEmpty();
    }

    // ── 3. Old-checksum → validate fails → repair → migrate ok → data untouched (target V35) ─

    private Path oldMigrationSet(Path dir) throws Exception {
        Path src = Path.of("src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(src)) {
            files.forEach(p -> {
                try { Files.copy(p, dir.resolve(p.getFileName())); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        Path v10 = dir.resolve("V10__init_diagnostic_templates.sql");
        String repaired = Files.readString(v10);
        String original = repaired.replace("true, NULL, NOW(), NOW()", "true, 1, NOW(), NOW()");
        assertThat(original).as("inverse of the one-token repair applied").isNotEqualTo(repaired);
        Files.writeString(v10, original);
        return dir;
    }

    @Test
    @DisplayName("A) old V10 applied (pre-existing user) → B) repaired V10 fails validation → C) repair → D) migrate ok → E) data & row untouched")
    void oldChecksumThenRepair(@TempDir Path tempDir) throws Exception {
        wipe();
        String oldLoc = "filesystem:" + oldMigrationSet(tempDir).toAbsolutePath();

        // Stage A: schema to V9, seed a pre-existing user so ORIGINAL V10 (created_by=1) can apply,
        // then migrate to V35 with the OLD set -> flyway_schema_history(version=10) holds the OLD checksum.
        flyway(oldLoc, "9").migrate();
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO users (id, username, email, password_hash, active, created_at) "
                    + "VALUES (1, 'fixture_pre_existing', 'fixture@test.local', 'x', true, NOW())");
        }
        flyway(oldLoc, TARGET_BEFORE_V36).migrate();

        assertThat(scalar("SELECT COUNT(*) FROM inspection_templates WHERE name = '" + SEDAN + "'")).isEqualTo(1);
        assertThat(scalar("SELECT created_by FROM inspection_templates WHERE name = '" + SEDAN + "'"))
                .as("old V10 seeded created_by = 1").isEqualTo(1L);
        long oldChecksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version = '10'");
        long historyRowsBefore = scalar("SELECT COUNT(*) FROM flyway_schema_history");
        long v10InstalledOnBefore = scalar("SELECT EXTRACT(EPOCH FROM installed_on)::bigint FROM flyway_schema_history WHERE version = '10'");
        long v10InstalledRankBefore = scalar("SELECT installed_rank FROM flyway_schema_history WHERE version = '10'");

        // Stage B: present the NEW (repaired) V10 -> validate & migrate must fail on checksum, mutate nothing.
        Flyway repaired = flyway(LOC, TARGET_BEFORE_V36);
        assertThatThrownBy(repaired::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum mismatch").hasMessageContaining("version 10");
        assertThatThrownBy(repaired::migrate)
                .isInstanceOf(FlywayValidateException.class).hasMessageContaining("version 10");
        assertThat(scalar("SELECT created_by FROM inspection_templates WHERE name = '" + SEDAN + "'")).isEqualTo(1L);
        assertThat(scalar("SELECT checksum FROM flyway_schema_history WHERE version = '10'")).isEqualTo(oldChecksum);

        // Stage C: repair (disposable DB only).
        repaired.repair();

        // Stage D: migrate again -> validation passes, nothing pending up to the target.
        repaired.migrate();
        assertThat(repaired.info().pending()).isEmpty();

        // Stage E: repair only rewrote metadata — it did NOT re-run any migration.
        assertThat(scalar("SELECT COUNT(*) FROM flyway_schema_history"))
                .as("repair+migrate added no history rows (no migration re-executed)")
                .isEqualTo(historyRowsBefore);
        assertThat(scalar("SELECT installed_rank FROM flyway_schema_history WHERE version = '10'"))
                .as("V10 history row not re-inserted (installed_rank unchanged)")
                .isEqualTo(v10InstalledRankBefore);
        assertThat(scalar("SELECT EXTRACT(EPOCH FROM installed_on)::bigint FROM flyway_schema_history WHERE version = '10'"))
                .as("V10 installed_on unchanged — repair only rewrites checksum/description/type/script")
                .isEqualTo(v10InstalledOnBefore);
        assertThat(scalar("SELECT COUNT(*) FROM inspection_templates WHERE name = '" + SEDAN + "'"))
                .as("no duplicate sedan template").isEqualTo(1);
        assertThat(scalar("SELECT created_by FROM inspection_templates WHERE name = '" + SEDAN + "'"))
                .as("pre-existing created_by = 1 preserved — repair did NOT re-run V10 SQL").isEqualTo(1L);
        long newChecksum = scalar("SELECT checksum FROM flyway_schema_history WHERE version = '10'");
        assertThat(newChecksum).as("stored checksum updated by repair").isNotEqualTo(oldChecksum);
        int repairedFileChecksum = Stream.of(flyway(LOC, null).info().all())
                .filter(m -> m.getVersion() != null && "10".equals(m.getVersion().getVersion()))
                .findFirst().orElseThrow().getChecksum();
        assertThat(newChecksum).as("stored checksum equals the repaired V10 file's Flyway checksum")
                .isEqualTo((long) repairedFileChecksum);
        assertThat(scalar("SELECT COUNT(*) FROM users")).as("application data (users) untouched by repair").isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM users WHERE username = 'fixture_pre_existing'")).isEqualTo(1);
    }
}
