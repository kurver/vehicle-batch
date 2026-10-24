package nz.co.krbconsulting.batch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nz.co.krbconsulting.batch.domain.ComplianceFlags;
import nz.co.krbconsulting.batch.domain.ComplianceUpdate;
import nz.co.krbconsulting.batch.domain.Exemption;
import nz.co.krbconsulting.batch.domain.Expiry;
import nz.co.krbconsulting.batch.domain.RegistrationStatus;
import nz.co.krbconsulting.batch.domain.Vehicle;
import nz.co.krbconsulting.batch.domain.VehicleCompliance;
import nz.co.krbconsulting.batch.domain.VehicleUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Row mapping and Y/N conversion, and the writer's handling of rows that have gone. */
@Testcontainers
class ComplianceJdbcTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScripts("db/test-schema.sql", "db/postgres/vehicle_status_trigger.sql");

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    static SimpleDriverDataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM vehicle"); // trigger removes the status rows
    }

    @Test
    void mapsVehicleFactsAndStoredFlags() {
        vehicle(1, DAY, null, 11, 5, DAY.plusDays(3));
        storeFlags(1, "Y", "N", "Y", "N", "Y");

        assertThat(read(1)).isEqualTo(new VehicleCompliance(
                new Vehicle(1, new Expiry(DAY), new Expiry(null), new VehicleUsage(11), new RegistrationStatus(5),
                        new Exemption(DAY.plusDays(3))),
                new ComplianceFlags(true, false, true, false, true)));
    }

    @Test
    void nullSourceColumnsMapToNullValues() {
        vehicle(1, null, null, null, null, null);

        assertThat(read(1).vehicle()).isEqualTo(new Vehicle(1, new Expiry(null), new Expiry(null),
                new VehicleUsage(null), new RegistrationStatus(null), new Exemption(null)));
    }

    @Test
    void anyNullStoredFlagMeansTheRecordedFlagsAreUnknown() {
        vehicle(1, null, null, 1, 1, null);
        storeFlags(1, "Y", "Y", "N", null, "N");

        assertThat(read(1).recorded()).isNull();
    }

    @Test
    void writerStoresYAndN() throws Exception {
        vehicle(1, null, null, 1, 1, null);

        writer().write(Chunk.of(new ComplianceUpdate(1, new ComplianceFlags(false, true, true, false, true))));

        assertThat(flags(1)).containsExactly("N", "Y", "Y", "N", "Y");
    }

    @Test
    void vehicleDeletedBetweenReadAndWriteIsSkipped() throws Exception {
        vehicle(1, DAY.minusDays(1), null, 1, 1, null);
        vehicle(2, DAY.minusDays(1), null, 1, 1, null);
        jdbc.update("UPDATE vehicle_status SET inspection_valid_fl = 'Y'"); // both stale as of DAY

        List<ComplianceUpdate> updates = new ArrayList<>();
        JdbcPagingItemReader<VehicleCompliance> reader = ComplianceJdbc.staleComplianceReader(dataSource, DAY, 10);
        reader.open(new ExecutionContext());
        for (VehicleCompliance row = reader.read(); row != null; row = reader.read()) {
            updates.add(row.reassessedOn(DAY).orElseThrow());
        }
        reader.close();
        jdbc.update("DELETE FROM vehicle WHERE vehicle_id = 1"); // trigger removes its status row

        writer().write(new Chunk<>(updates));

        assertThat(updates).hasSize(2);
        assertThat(flags(2)).containsExactly("N", "Y", "N", "N", "N");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_status", Integer.class)).isEqualTo(1);
    }

    private static JdbcBatchItemWriter<ComplianceUpdate> writer() {
        JdbcBatchItemWriter<ComplianceUpdate> writer = ComplianceJdbc.complianceWriter(dataSource);
        writer.afterPropertiesSet();
        return writer;
    }

    private VehicleCompliance read(int id) {
        return jdbc.queryForObject("SELECT " + ComplianceSql.SELECT + " FROM " + ComplianceSql.FROM
                + " WHERE vehicle_id = ?", ComplianceJdbc::mapRow, id);
    }

    private void vehicle(int id, LocalDate inspection, LocalDate licence, Integer usage, Integer regtStatus,
            LocalDate exemptEnd) {
        jdbc.update("INSERT INTO vehicle (vehicle_id, inspection_expiry_dt, licence_expiry_dt, vehicle_usage_cd, "
                + "regt_status_cd, exempt_end_dt) VALUES (?, ?, ?, ?, ?, ?)",
                id, inspection, licence, usage, regtStatus, exemptEnd);
    }

    private void storeFlags(int id, String inspection, String licence, String aOrBRego, String lapsed, String exempt) {
        jdbc.update("UPDATE vehicle_status SET inspection_valid_fl = ?, licence_valid_fl = ?, a_or_b_rego_fl = ?, "
                + "lapsed_fl = ?, exempt_fl = ? WHERE vehicle_id = ?",
                inspection, licence, aOrBRego, lapsed, exempt, id);
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
