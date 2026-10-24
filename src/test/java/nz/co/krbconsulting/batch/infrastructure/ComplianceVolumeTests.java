package nz.co.krbconsulting.batch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real job over a production-sized table (12M vehicles by default) to see how it scales: run times, the
 * plan of the page query, and per-statement timings. Opt-in, see README-volume-test.md: {@code ./mvnw verify -Pvolume}.
 *
 * <p>The scenarios run in order against one data set: a normal day, a rerun with nothing to do, and the backfill of a
 * new flag column. Results are printed and written to {@code target/volume-test-results.md}.
 */
@Tag("volume")
@Testcontainers
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplianceVolumeTests {

    static final int VEHICLES = Integer.getInteger("volume.vehicles", 12_000_000);
    static final int CHUNK_SIZE = 1000; // the job's default; the test doesn't override it

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScripts("db/test-schema.sql", "db/postgres/vehicle_status_trigger.sql")
            .withSharedMemorySize(1024L * 1024 * 1024)
            // replaces Testcontainers' default command, so fsync stays on and commits cost what they really cost
            .withCommand("postgres",
                    "-c", "shared_buffers=2GB",
                    "-c", "max_wal_size=8GB",
                    "-c", "shared_preload_libraries=pg_stat_statements");

    /** The day the job runs as; the data is generated around it. */
    static final LocalDate DAY = LocalDate.now();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job complianceJob;

    final List<String> results = new ArrayList<>();
    int runs;

    @BeforeAll
    void load() {
        jdbc.execute("CREATE EXTENSION pg_stat_statements");
        Duration took = time(() -> {
            // loaded with the trigger off and the flags computed as of yesterday: the state after yesterday's run
            jdbc.execute("ALTER TABLE vehicle DISABLE TRIGGER vehicle_status_sync_trg");
            jdbc.execute("SELECT setseed(0.42); " + """
                    INSERT INTO vehicle (vehicle_id, registration, inspection_expiry_dt, licence_expiry_dt,
                                         vehicle_usage_cd, regt_status_cd, exempt_end_dt)
                    SELECT g, 'R' || g,
                           CASE WHEN random() < 0.03 THEN NULL ELSE DATE '%1$s' - 365 + floor(random() * 730)::int END,
                           CASE WHEN random() < 0.05 THEN NULL ELSE DATE '%1$s' - 180 + floor(random() * 365)::int END,
                           CASE WHEN random() < 0.03 THEN 10 WHEN random() < 0.01 THEN 11
                                ELSE 1 + floor(random() * 9)::int END,
                           CASE WHEN random() < 0.06 THEN 5 ELSE 1 END,
                           CASE WHEN random() < 0.02 THEN DATE '%1$s' - 30 + floor(random() * 90)::int END
                      FROM generate_series(1, %2$d) g""".formatted(DAY, VEHICLES));
            jdbc.execute("""
                    INSERT INTO vehicle_status
                    SELECT vehicle_id,
                           CASE WHEN inspection_expiry_dt IS NULL OR inspection_expiry_dt >= DATE '%1$s' - 1
                                THEN 'Y' ELSE 'N' END,
                           CASE WHEN licence_expiry_dt IS NULL OR licence_expiry_dt >= DATE '%1$s' - 1
                                THEN 'Y' ELSE 'N' END,
                           CASE WHEN vehicle_usage_cd IN (10, 11) THEN 'Y' ELSE 'N' END,
                           CASE WHEN regt_status_cd = 5 THEN 'Y' ELSE 'N' END,
                           CASE WHEN exempt_end_dt >= DATE '%1$s' - 1 THEN 'Y' ELSE 'N' END
                      FROM vehicle""".formatted(DAY));
            jdbc.execute("ALTER TABLE vehicle ENABLE TRIGGER vehicle_status_sync_trg");
            jdbc.execute("VACUUM ANALYZE vehicle");
            jdbc.execute("VACUUM ANALYZE vehicle_status");
        });
        String size = jdbc.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class);
        results.add("# Volume test results\n\n%,d vehicles as of %s, chunk size %d. Loaded in %s; database %s.\n"
                .formatted(VEHICLES, DAY, CHUNK_SIZE, format(took), size));
    }

    @Test
    @Order(1)
    void normalDay() {
        long expected = jdbc.queryForObject("""
                SELECT count(*) FROM vehicle
                 WHERE inspection_expiry_dt = ? OR licence_expiry_dt = ? OR exempt_end_dt = ?""",
                Long.class, DAY.minusDays(1), DAY.minusDays(1), DAY.minusDays(1));
        pagePlans("Normal day");

        StepExecution step = run("Normal day: yesterday's dates have passed", DAY);

        assertThat(step.getReadCount()).isEqualTo(expected);
        assertThat(step.getWriteCount()).isEqualTo(expected);
    }

    @Test
    @Order(2)
    void nothingToDo() {
        StepExecution step = run("Rerun with nothing to do", DAY);

        assertThat(step.getReadCount()).isZero();
    }

    @Test
    @Order(3)
    @Timeout(value = 4, unit = TimeUnit.HOURS)
    void backfillOfANewFlagColumn() {
        // the state after the DBAs add a flag column: NULL on every row
        jdbc.execute("ALTER TABLE vehicle_status DROP COLUMN exempt_fl");
        jdbc.execute("ALTER TABLE vehicle_status ADD COLUMN exempt_fl CHAR(1)");
        pagePlans("Backfill");

        StepExecution step = run("Backfill: new flag column, every row NULL", DAY);

        assertThat(step.getWriteCount()).isEqualTo(VEHICLES);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_status WHERE exempt_fl IS NULL", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_status WHERE exempt_fl = 'Y'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM vehicle WHERE exempt_end_dt >= ?",
                        Long.class, DAY));
    }

    @AfterAll
    void report() throws IOException {
        String report = String.join("\n", results);
        System.out.println(report);
        Files.writeString(Path.of("target", "volume-test-results.md"), report);
    }

    private StepExecution run(String scenario, LocalDate asOf) {
        jdbc.execute("SELECT pg_stat_statements_reset()");
        JobExecution[] execution = new JobExecution[1];
        Duration took = time(() -> {
            try {
                execution[0] = jobOperator.start(complianceJob, new JobParametersBuilder()
                        .addString("asOf", asOf.toString()).addLong("run", (long) ++runs).toJobParameters());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(execution[0].getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        StepExecution step = execution[0].getStepExecutions().iterator().next();

        results.add("""
                ## %s

                | Run time | Read | Updated | Unchanged | Commits |
                |---|---|---|---|---|
                | %s | %,d | %,d | %,d | %,d |

                Statements (pg_stat_statements, times in ms):

                | Statement | Calls | Mean | Max | Total |
                |---|---|---|---|---|
                %s
                """.formatted(scenario, format(took), step.getReadCount(), step.getWriteCount(),
                step.getFilterCount(), step.getCommitCount(), statementStats()));
        return step;
    }

    /** The job's page and update statements, slowest in total first. */
    private String statementStats() {
        List<String> rows = jdbc.query("""
                SELECT CASE WHEN query LIKE 'UPDATE%' THEN 'update' WHEN query LIKE '%vehicle_id >%'
                            THEN 'page (2nd onwards)' ELSE 'page (first)' END,
                       calls, round(mean_exec_time::numeric, 1), round(max_exec_time::numeric, 1),
                       round(total_exec_time::numeric)
                  FROM pg_stat_statements
                 WHERE query LIKE '%vehicle_status%' AND query NOT LIKE '%pg_stat_statements%'
                   AND (query LIKE 'UPDATE%' OR query LIKE 'SELECT%LIMIT%')
                 ORDER BY total_exec_time DESC""",
                (rs, n) -> "| %s | %,d | %s | %s | %s |".formatted(rs.getString(1), rs.getLong(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)));
        return String.join("\n", rows);
    }

    /** EXPLAIN ANALYZE of the job's page query, at the start of the table and halfway through. */
    private void pagePlans(String scenario) {
        var provider = new PostgresPagingQueryProvider();
        provider.setSelectClause(ComplianceSql.SELECT);
        provider.setFromClause(ComplianceSql.FROM);
        provider.setWhereClause(ComplianceSql.WHERE);
        provider.setSortKeys(Map.of(ComplianceSql.SORT_KEY,
                org.springframework.batch.infrastructure.item.database.Order.ASCENDING));
        String asOf = "DATE '" + DAY + "'";
        String first = provider.generateFirstPageQuery(CHUNK_SIZE).replace(":asOf", asOf);
        String later = provider.generateRemainingPagesQuery(CHUNK_SIZE).replace(":asOf", asOf)
                .replace("(vehicle_id > ?)", "(vehicle_id > " + VEHICLES / 2 + ")");

        plan(scenario + ": first page", first);
        plan(scenario + ": page starting halfway", later);
    }

    private void plan(String label, String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
        results.add("### Plan, %s\n\n```\n%s\n```\n".formatted(label, String.join("\n", lines)));
    }

    private static Duration time(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static String format(Duration d) {
        return d.toMinutes() > 0 ? "%dm %02ds".formatted(d.toMinutes(), d.toSecondsPart())
                : "%.1fs".formatted(d.toMillis() / 1000.0);
    }
}
