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
import org.springframework.batch.core.launch.JobLauncher;
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobLauncher jobLauncher;

    @Autowired
    Job complianceJob;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM vehicle"); // trigger removes the status rows
    }

    @Test
    void triggerKeepsStatusInStepWithVehicle() {
        vehicle(1, TODAY.plusDays(5), null);
        assertThat(status(1)).containsEntry("inspection_valid_fl", "Y").containsEntry("licence_valid_fl", "Y");

        jdbc.update("UPDATE vehicle SET inspection_expiry_dt = ? WHERE vehicle_id = 1", TODAY.minusDays(1));
        assertThat(status(1)).containsEntry("inspection_valid_fl", "N").containsEntry("licence_valid_fl", "Y");

        jdbc.update("DELETE FROM vehicle WHERE vehicle_id = 1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_status", Integer.class)).isZero();
    }

    @Test
    void jobExpiresOnlyWhatHasPassedItsDate() throws Exception {
        LocalDate asOf = TODAY.plusDays(10);
        vehicle(1, asOf.minusDays(1), null);          // inspection lapses
        vehicle(2, asOf, asOf);                       // valid through the expiry date itself
        vehicle(3, null, asOf.minusDays(3));          // licence lapses, no inspection date
        vehicle(4, asOf.minusDays(2), asOf.minusDays(2)); // both lapse
        vehicle(5, asOf.plusDays(30), asOf.plusDays(30)); // untouched
        vehicle(6, TODAY.minusDays(1), null);         // already N from the trigger

        JobExecution execution = jobLauncher.run(complianceJob,
                new JobParametersBuilder().addString("asOf", asOf.toString()).toJobParameters());

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(flags(1)).containsExactly("N", "Y");
        assertThat(flags(2)).containsExactly("Y", "Y");
        assertThat(flags(3)).containsExactly("Y", "N");
        assertThat(flags(4)).containsExactly("N", "N");
        assertThat(flags(5)).containsExactly("Y", "Y");
        assertThat(flags(6)).containsExactly("N", "Y");
    }

    @Test
    void rerunIsHarmlessAndCreatesNoBatchTables() throws Exception {
        LocalDate asOf = TODAY.plusDays(11);
        vehicle(1, asOf.minusDays(1), null);
        var params = new JobParametersBuilder().addString("asOf", asOf.toString());

        assertThat(jobLauncher.run(complianceJob, params.toJobParameters()).getExitStatus().getExitCode())
                .isEqualTo("COMPLETED");
        // each CronJob pod is a fresh JVM with an empty in-memory repository; "attempt" stands in for that here
        JobExecution again = jobLauncher.run(complianceJob, params.addLong("attempt", 2L).toJobParameters());

        assertThat(again.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(again.getStepExecutions().iterator().next().getReadCount()).isZero();
        assertThat(flags(1)).containsExactly("N", "Y");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'batch\\_%'", Integer.class))
                .isZero();
    }

    @Test
    void defaultsToToday() throws Exception {
        vehicle(1, TODAY, TODAY.minusDays(1));
        jdbc.update("UPDATE vehicle_status SET licence_valid_fl = 'Y' WHERE vehicle_id = 1"); // simulate going stale

        assertThat(jobLauncher.run(complianceJob, new JobParametersBuilder().toJobParameters())
                .getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(flags(1)).containsExactly("Y", "N");
    }

    private void vehicle(int id, LocalDate inspection, LocalDate licence) {
        jdbc.update("INSERT INTO vehicle (vehicle_id, inspection_expiry_dt, licence_expiry_dt) VALUES (?, ?, ?)",
                id, inspection, licence);
    }

    private Map<String, Object> status(int id) {
        return jdbc.queryForMap("SELECT * FROM vehicle_status WHERE vehicle_id = ?", id);
    }

    private List<String> flags(int id) {
        Map<String, Object> row = status(id);
        return List.of((String) row.get("inspection_valid_fl"), (String) row.get("licence_valid_fl"));
    }
}
