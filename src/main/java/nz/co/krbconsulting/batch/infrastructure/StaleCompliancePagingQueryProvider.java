package nz.co.krbconsulting.batch.infrastructure;

import java.util.Map;
import javax.sql.DataSource;

import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.PagingQueryProvider;

/**
 * The job's own page queries for {@code JdbcPagingItemReader}, instead of one generated per database. One plain SQL
 * query works on DB2 and Postgres, and lets the last key be applied to both tables (see
 * {@link ComplianceSql#AFTER_LAST_KEY}). The reader binds that key as {@code :_vehicle_id} from the result column
 * {@code vehicle_id}.
 */
final class StaleCompliancePagingQueryProvider implements PagingQueryProvider {

    private static final Map<String, Order> SORT_KEYS = Map.of(ComplianceSql.SORT_KEY, Order.ASCENDING);

    @Override
    public void init(DataSource dataSource) {
    }

    @Override
    public String generateFirstPageQuery(int pageSize) {
        return ComplianceSql.firstPage(pageSize);
    }

    @Override
    public String generateRemainingPagesQuery(int pageSize) {
        return ComplianceSql.nextPage(pageSize);
    }

    @Override
    public int getParameterCount() {
        return 2; // asOf and the last key, each bound by name
    }

    @Override
    public boolean isUsingNamedParameters() {
        return true;
    }

    @Override
    public Map<String, Order> getSortKeys() {
        return SORT_KEYS;
    }

    @Override
    public String getSortKeyPlaceHolder(String keyName) {
        return ":_" + keyName;
    }

    @Override
    public Map<String, Order> getSortKeysWithoutAliases() {
        return SORT_KEYS;
    }
}
