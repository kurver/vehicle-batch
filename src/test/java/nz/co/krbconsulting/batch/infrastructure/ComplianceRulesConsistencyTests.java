package nz.co.krbconsulting.batch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import nz.co.krbconsulting.batch.domain.VehicleCompliance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The rules live in three places: the domain ({@code Vehicle#flagsOn}, authoritative), the job's WHERE clause and the
 * trigger. This checks the other two against the domain over every combination of boundary inputs.
 */
@Testcontainers
class ComplianceRulesConsistencyTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScripts("db/test-schema.sql", "db/postgres/vehicle_status_trigger.sql");

    private static final String[] FLAG_COLUMNS =
            {"inspection_valid_fl", "licence_valid_fl", "a_or_b_rego_fl", "lapsed_fl", "exempt_fl"};

    static SimpleDriverDataSource dataSource;
    static JdbcTemplate jdbc;

    /** The database's CURRENT_DATE, which the trigger uses; dates are generated around it. */
    LocalDate today;

    @BeforeAll
    static void connect() {
        dataSource = new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void insertEveryCombination() {
        jdbc.update("DELETE FROM vehicle");
        today = jdbc.queryForObject("SELECT CURRENT_DATE", LocalDate.class);

        List<LocalDate> dates = Arrays.asList(null, today.minusDays(1), today, today.plusDays(1));
        List<Integer> usages = Arrays.asList(null, 1, 10, 11, 12);
        List<Integer> statuses = Arrays.asList(null, 1, 5);
        List<Object[]> rows = new ArrayList<>();
        int id = 0;
        for (LocalDate inspection : dates) {
            for (LocalDate licence : dates) {
                for (Integer usage : usages) {
                    for (Integer status : statuses) {
                        for (LocalDate exemptEnd : dates) {
                            rows.add(new Object[] {++id, inspection, licence, usage, status, exemptEnd});
                        }
                    }
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO vehicle (vehicle_id, inspection_expiry_dt, licence_expiry_dt, vehicle_usage_cd, "
                + "regt_status_cd, exempt_end_dt) VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    @Test
    void triggerSetsTheFlagsTheDomainExpects() {
        List<VehicleCompliance> all = allRows();

        assertThat(all).hasSize(960);
        assertThat(all).allSatisfy(row ->
                assertThat(row.recorded()).as("vehicle %d", row.vehicle().vehicleId())
                        .isEqualTo(row.vehicle().flagsOn(today)));
    }

    @Test
    void readerSelectsExactlyTheRowsTheDomainCallsStale() throws Exception {
        driftFlags();

        for (int offset = -2; offset <= 2; offset++) {
            LocalDate asOf = today.plusDays(offset);
            Set<Long> expected = allRows().stream()
                    .filter(row -> row.reassessedOn(asOf).isPresent())
                    .map(row -> row.vehicle().vehicleId())
                    .collect(Collectors.toCollection(TreeSet::new));

            assertThat(staleIdsRead(asOf)).as("as of %s", asOf).isNotEmpty().isEqualTo(expected);
        }
    }

    /**
     * Spreads drift across the combinations by vehicle_id mod 11: 0 is left as the trigger set it, 1-5 flip one flag
     * and 6-10 set one flag NULL.
     */
    private void driftFlags() {
        for (int i = 0; i < FLAG_COLUMNS.length; i++) {
            String column = FLAG_COLUMNS[i];
            jdbc.update("UPDATE vehicle_status SET " + column + " = CASE " + column + " WHEN 'Y' THEN 'N' ELSE 'Y' END "
                    + "WHERE MOD(vehicle_id, 11) = ?", i + 1);
            jdbc.update("UPDATE vehicle_status SET " + column + " = NULL WHERE MOD(vehicle_id, 11) = ?", i + 6);
        }
    }

    private List<VehicleCompliance> allRows() {
        return jdbc.query("SELECT " + ComplianceSql.SELECT + " FROM " + ComplianceSql.FROM, ComplianceJdbc::mapRow);
    }

    /** Through the job's own reader, so paging is exercised too. */
    private Set<Long> staleIdsRead(LocalDate asOf) throws Exception {
        JdbcPagingItemReader<VehicleCompliance> reader = ComplianceJdbc.staleComplianceReader(dataSource, asOf, 50);
        reader.open(new ExecutionContext());
        try {
            Set<Long> ids = new TreeSet<>();
            for (VehicleCompliance row = reader.read(); row != null; row = reader.read()) {
                assertThat(ids.add(row.vehicle().vehicleId())).as("read once").isTrue();
            }
            return ids;
        } finally {
            reader.close();
        }
    }
}
