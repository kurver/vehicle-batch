package nz.co.krbconsulting.batch.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;

import nz.co.krbconsulting.batch.domain.ComplianceFlags;
import nz.co.krbconsulting.batch.domain.ComplianceUpdate;
import nz.co.krbconsulting.batch.domain.Exemption;
import nz.co.krbconsulting.batch.domain.Expiry;
import nz.co.krbconsulting.batch.domain.RegistrationStatus;
import nz.co.krbconsulting.batch.domain.Vehicle;
import nz.co.krbconsulting.batch.domain.VehicleCompliance;
import nz.co.krbconsulting.batch.domain.VehicleUsage;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** JDBC adapters between the legacy tables and the domain. */
public final class ComplianceJdbc {

    private ComplianceJdbc() {
    }

    /**
     * Keyset-paged (vehicle_id &gt; last) rather than OFFSET, so every page is a short range query. No reader state
     * is saved: committed rows drop out of the query, so a fresh run naturally picks up where a failed one stopped.
     */
    public static JdbcPagingItemReader<VehicleCompliance> staleComplianceReader(
            DataSource dataSource, LocalDate asOf, int pageSize) throws Exception {
        var queryProvider = new SqlPagingQueryProviderFactoryBean();
        queryProvider.setDataSource(dataSource);
        queryProvider.setSelectClause(ComplianceSql.SELECT);
        queryProvider.setFromClause(ComplianceSql.FROM);
        queryProvider.setWhereClause(ComplianceSql.WHERE);
        queryProvider.setSortKeys(Map.of(ComplianceSql.SORT_KEY, Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<VehicleCompliance>()
                .name("staleComplianceReader")
                .saveState(false)
                .dataSource(dataSource)
                .queryProvider(queryProvider.getObject())
                .parameterValues(Map.of("asOf", asOf))
                .pageSize(pageSize)
                .fetchSize(pageSize)
                .rowMapper(ComplianceJdbc::mapRow)
                .build();
    }

    /** Only the status table is written; the legacy vehicle table is never touched by the job. */
    public static JdbcBatchItemWriter<ComplianceUpdate> complianceWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<ComplianceUpdate>()
                .dataSource(dataSource)
                .sql(ComplianceSql.UPDATE)
                .itemSqlParameterSourceProvider(update -> {
                    ComplianceFlags flags = update.flags();
                    return new MapSqlParameterSource()
                            .addValue("vehicleId", update.vehicleId())
                            .addValue("inspectionFlag", flag(flags.inspectionValid()))
                            .addValue("licenceFlag", flag(flags.licenceValid()))
                            .addValue("aOrBRegoFlag", flag(flags.aOrBRego()))
                            .addValue("lapsedFlag", flag(flags.lapsed()))
                            .addValue("exemptFlag", flag(flags.exempt()));
                })
                // a vehicle deleted since it was read has no status row left to update; that is fine
                .assertUpdates(false)
                .build();
    }

    static VehicleCompliance mapRow(ResultSet rs, int rowNum) throws SQLException {
        var vehicle = new Vehicle(
                rs.getLong("vehicle_id"),
                new Expiry(rs.getObject("inspection_expiry_dt", LocalDate.class)),
                new Expiry(rs.getObject("licence_expiry_dt", LocalDate.class)),
                new VehicleUsage(rs.getObject("vehicle_usage_cd", Integer.class)),
                new RegistrationStatus(rs.getObject("regt_status_cd", Integer.class)),
                new Exemption(rs.getObject("exempt_end_dt", LocalDate.class)));
        return new VehicleCompliance(vehicle, recordedFlags(rs));
    }

    /** The stored flags, or null if any is missing so the row is always rewritten. */
    private static ComplianceFlags recordedFlags(ResultSet rs) throws SQLException {
        String inspection = rs.getString("inspection_valid_fl");
        String licence = rs.getString("licence_valid_fl");
        String aOrBRego = rs.getString("a_or_b_rego_fl");
        String lapsed = rs.getString("lapsed_fl");
        String exempt = rs.getString("exempt_fl");
        if (inspection == null || licence == null || aOrBRego == null || lapsed == null || exempt == null) {
            return null;
        }
        return new ComplianceFlags(isYes(inspection), isYes(licence), isYes(aOrBRego), isYes(lapsed), isYes(exempt));
    }

    private static boolean isYes(String flag) {
        return "Y".equals(flag);
    }

    private static String flag(boolean value) {
        return value ? "Y" : "N";
    }
}
