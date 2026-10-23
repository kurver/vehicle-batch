package nz.co.krbconsulting.batch.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;

import nz.co.krbconsulting.batch.domain.Expiry;
import nz.co.krbconsulting.batch.domain.VehicleCompliance;
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
     * Keyset-paged (vehicle_id &gt; last) rather than OFFSET, so every page is a short index-range query. No reader
     * state is saved: committed rows drop out of the query, so a fresh run naturally picks up where a failed one
     * stopped.
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
    public static JdbcBatchItemWriter<VehicleCompliance> complianceWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<VehicleCompliance>()
                .dataSource(dataSource)
                .sql(ComplianceSql.UPDATE)
                .itemSqlParameterSourceProvider(c -> new MapSqlParameterSource()
                        .addValue("vehicleId", c.vehicleId())
                        .addValue("inspectionFlag", flag(c.inspectionValid()))
                        .addValue("licenceFlag", flag(c.licenceValid())))
                // a vehicle deleted since it was read has no status row left to update; that is fine
                .assertUpdates(false)
                .build();
    }

    private static VehicleCompliance mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VehicleCompliance(
                rs.getLong("vehicle_id"),
                new Expiry(rs.getObject("inspection_expiry_dt", LocalDate.class)),
                new Expiry(rs.getObject("licence_expiry_dt", LocalDate.class)),
                isYes(rs.getString("inspection_valid_fl")),
                isYes(rs.getString("licence_valid_fl")));
    }

    private static boolean isYes(String flag) {
        return "Y".equals(flag);
    }

    private static String flag(boolean value) {
        return value ? "Y" : "N";
    }
}
