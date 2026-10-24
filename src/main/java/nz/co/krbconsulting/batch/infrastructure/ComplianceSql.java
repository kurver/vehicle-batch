package nz.co.krbconsulting.batch.infrastructure;

/**
 * Legacy schema names and queries, kept in one place. Plain ANSI SQL so it runs on DB2 and Postgres.
 *
 * <p>The job selects only rows where some stored flag disagrees with the rules as of {@code :asOf}; the rules are
 * repeated here from the domain so the database filters the rows out before they reach the job. Mostly that is
 * dates passing, but it also corrects anything the trigger missed and fills NULL flags (COALESCE to '?', which never
 * matches). The domain ({@code Vehicle#flagsOn}) is authoritative; keep this and the trigger in line with it.
 */
final class ComplianceSql {

    static final String SELECT = """
            v.vehicle_id, v.inspection_expiry_dt, v.licence_expiry_dt, v.vehicle_usage_cd, v.regt_status_cd, \
            v.exempt_end_dt, \
            s.inspection_valid_fl, s.licence_valid_fl, s.a_or_b_rego_fl, s.lapsed_fl, s.exempt_fl""";

    static final String FROM = "vehicle v JOIN vehicle_status s ON s.vehicle_id = v.vehicle_id";

    static final String WHERE = """
            (COALESCE(inspection_valid_fl, '?') <> \
            CASE WHEN inspection_expiry_dt IS NULL OR inspection_expiry_dt >= :asOf THEN 'Y' ELSE 'N' END \
            OR COALESCE(licence_valid_fl, '?') <> \
            CASE WHEN licence_expiry_dt IS NULL OR licence_expiry_dt >= :asOf THEN 'Y' ELSE 'N' END \
            OR COALESCE(a_or_b_rego_fl, '?') <> CASE WHEN vehicle_usage_cd IN (10, 11) THEN 'Y' ELSE 'N' END \
            OR COALESCE(lapsed_fl, '?') <> CASE WHEN regt_status_cd = 5 THEN 'Y' ELSE 'N' END \
            OR COALESCE(exempt_fl, '?') <> CASE WHEN exempt_end_dt >= :asOf THEN 'Y' ELSE 'N' END)""";

    /** The paging key, as the result column the reader takes the last value from. */
    static final String SORT_KEY = "vehicle_id";

    /**
     * Keyset paging after the last key read. The key is bound once and compared on both tables so that each table's
     * index scan starts at the key; Postgres doesn't carry a range condition across a join, so with only
     * {@code v.vehicle_id > ...} it read vehicle_status from the start on every page.
     */
    static final String AFTER_LAST_KEY = "v.vehicle_id > :_vehicle_id AND s.vehicle_id > :_vehicle_id";

    static final String UPDATE = """
            UPDATE vehicle_status \
            SET inspection_valid_fl = :inspectionFlag, licence_valid_fl = :licenceFlag, \
            a_or_b_rego_fl = :aOrBRegoFlag, lapsed_fl = :lapsedFlag, exempt_fl = :exemptFlag \
            WHERE vehicle_id = :vehicleId""";

    private ComplianceSql() {
    }

    static String firstPage(int pageSize) {
        return page(WHERE, pageSize);
    }

    static String nextPage(int pageSize) {
        return page(AFTER_LAST_KEY + " AND " + WHERE, pageSize);
    }

    private static String page(String where, int pageSize) {
        return "SELECT " + SELECT + " FROM " + FROM + " WHERE " + where
                + " ORDER BY v.vehicle_id FETCH FIRST " + pageSize + " ROWS ONLY";
    }
}
