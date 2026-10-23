package nz.co.krbconsulting.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "compliance.chunk-size=2"})
class ComplianceJobTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScripts("db/test-schema.sql", "db/postgres/vehicle_status_trigger.sql");

    private static final LocalDate TODAY = LocalDate.now();
    private static final int ACTIVE = 1;
    private static final int LAPSED = 5;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job complianceJob;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM vehicle"); // trigger removes the status rows
    }

    @Test
    void triggerKeepsStatusInStepWithVehicle() {
        vehicle(1, TODAY.plusDays(5), null, 10, ACTIVE, TODAY.plusDays(5));
        // flags: inspection, licence, a/b rego, lapsed, exempt
        assertThat(flags(1)).containsExactly("Y", "Y", "Y", "N", "Y");

        jdbc.update("UPDATE vehicle SET inspection_expiry_dt = ?, regt_status_cd = ?, exempt_end_dt = ?, "
                + "vehicle_usage_cd = 3 WHERE vehicle_id = 1", TODAY.minusDays(1), LAPSED, TODAY.minusDays(1));
        assertThat(flags(1)).containsExactly("N", "Y", "N", "Y", "N");

        jdbc.update("DELETE FROM vehicle WHERE vehicle_id = 1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_status", Integer.class)).isZero();
    }

    @Test
    void jobExpiresOnlyWhatHasPassedItsDate() throws Exception {
        LocalDate asOf = TODAY.plusDays(10);
        vehicle(1, asOf.minusDays(1), null);              // inspection lapses
        vehicle(2, asOf, asOf);                           // valid through the expiry date itself
        vehicle(3, null, asOf.minusDays(3));              // licence lapses, no inspection date
        vehicle(4, asOf.minusDays(2), asOf.minusDays(2)); // both lapse
        vehicle(5, asOf.plusDays(30), asOf.plusDays(30)); // untouched
        vehicle(6, TODAY.minusDays(1), null);             // already N from the trigger
        vehicle(7, null, null, 1, ACTIVE, asOf.minusDays(1)); // exemption ends
        vehicle(8, null, null, 1, ACTIVE, asOf);              // exempt through the end date itself

        JobExecution execution = run(asOf);

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(flags(1)).containsExactly("N", "Y", "N", "N", "N");
        assertThat(flags(2)).containsExactly("Y", "Y", "N", "N", "N");
        assertThat(flags(3)).containsExactly("Y", "N", "N", "N", "N");
        assertThat(flags(4)).containsExactly("N", "N", "N", "N", "N");
        assertThat(flags(5)).containsExactly("Y", "Y", "N", "N", "N");
        assertThat(flags(6)).containsExactly("N", "Y", "N", "N", "N");
        assertThat(flags(7)).containsExactly("Y", "Y", "N", "N", "N");
        assertThat(flags(8)).containsExactly("Y", "Y", "N", "N", "Y");
    }

    @Test
    void jobCorrectsFlagsThatDriftedOrWereNeverSet() throws Exception {
        vehicle(1, null, null, 11, ACTIVE, null);
        vehicle(2, null, null, 1, LAPSED, null);
        vehicle(3, TODAY.plusDays(1), null, 10, ACTIVE, TODAY.plusDays(1));
        // as if changed behind the trigger's back, or new columns not yet populated
        jdbc.update("UPDATE vehicle_status SET a_or_b_rego_fl = 'N' WHERE vehicle_id = 1");
        jdbc.update("UPDATE vehicle_status SET lapsed_fl = NULL WHERE vehicle_id = 2");
        jdbc.update("UPDATE vehicle_status SET inspection_valid_fl = NULL, a_or_b_rego_fl = NULL, "
                + "exempt_fl = NULL WHERE vehicle_id = 3");

        JobExecution execution = run(TODAY);

        assertThat(execution.getStepExecutions().iterator().next().getWriteCount()).isEqualTo(3);
        assertThat(flags(1)).containsExactly("Y", "Y", "Y", "N", "N");
        assertThat(flags(2)).containsExactly("Y", "Y", "N", "Y", "N");
        assertThat(flags(3)).containsExactly("Y", "Y", "Y", "N", "Y");
    }

    @Test
    void rerunIsHarmlessAndCreatesNoBatchTables() throws Exception {
        LocalDate asOf = TODAY.plusDays(11);
        vehicle(1, asOf.minusDays(1), null);
        var params = new JobParametersBuilder().addString("asOf", asOf.toString());

        assertThat(jobOperator.start(complianceJob, params.toJobParameters()).getExitStatus().getExitCode())
                .isEqualTo("COMPLETED");
        // each CronJob pod is a fresh JVM with an empty in-memory repository; "attempt" stands in for that here
        JobExecution again = jobOperator.start(complianceJob, params.addLong("attempt", 2L).toJobParameters());

        assertThat(again.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(again.getStepExecutions().iterator().next().getReadCount()).isZero();
        assertThat(flags(1)).containsExactly("N", "Y", "N", "N", "N");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'batch\\_%'", Integer.class))
                .isZero();
    }

    @Test
    void defaultsToToday() throws Exception {
        vehicle(1, TODAY, TODAY.minusDays(1));
        jdbc.update("UPDATE vehicle_status SET licence_valid_fl = 'Y' WHERE vehicle_id = 1"); // simulate going stale

        assertThat(jobOperator.start(complianceJob, new JobParametersBuilder().toJobParameters())
                .getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(flags(1)).containsExactly("Y", "N", "N", "N", "N");
    }

    private JobExecution run(LocalDate asOf) throws Exception {
        return jobOperator.start(complianceJob,
                new JobParametersBuilder().addString("asOf", asOf.toString()).toJobParameters());
    }

    private void vehicle(int id, LocalDate inspection, LocalDate licence) {
        vehicle(id, inspection, licence, 1, ACTIVE, null);
    }

    private void vehicle(int id, LocalDate inspection, LocalDate licence, int usage, int regtStatus,
            LocalDate exemptEnd) {
        jdbc.update("INSERT INTO vehicle (vehicle_id, inspection_expiry_dt, licence_expiry_dt, vehicle_usage_cd, "
                + "regt_status_cd, exempt_end_dt) VALUES (?, ?, ?, ?, ?, ?)",
                id, inspection, licence, usage, regtStatus, exemptEnd);
    }

    /** inspection, licence, a/b rego, lapsed, exempt */
    private List<String> flags(int id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM vehicle_status WHERE vehicle_id = ?", id);
        return List.of(
                (String) row.get("inspection_valid_fl"),
                (String) row.get("licence_valid_fl"),
                (String) row.get("a_or_b_rego_fl"),
                (String) row.get("lapsed_fl"),
                (String) row.get("exempt_fl"));
    }
}
