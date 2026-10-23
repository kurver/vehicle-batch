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
            vehicle_id, inspection_expiry_dt, licence_expiry_dt, vehicle_usage_cd, regt_status_cd, exempt_end_dt, \
            inspection_valid_fl, licence_valid_fl, a_or_b_rego_fl, lapsed_fl, exempt_fl""";

    /**
     * A derived table so the paging key {@code vehicle_id} is unambiguous across the two tables; both databases
     * merge it into the outer query, so the predicates still reach the base tables.
     */
    static final String FROM = """
            (SELECT v.vehicle_id, v.inspection_expiry_dt, v.licence_expiry_dt, \
            v.vehicle_usage_cd, v.regt_status_cd, v.exempt_end_dt, \
            s.inspection_valid_fl, s.licence_valid_fl, s.a_or_b_rego_fl, s.lapsed_fl, s.exempt_fl \
            FROM vehicle v JOIN vehicle_status s ON s.vehicle_id = v.vehicle_id) c""";

    static final String WHERE = """
            COALESCE(inspection_valid_fl, '?') <> \
            CASE WHEN inspection_expiry_dt IS NULL OR inspection_expiry_dt >= :asOf THEN 'Y' ELSE 'N' END \
            OR COALESCE(licence_valid_fl, '?') <> \
            CASE WHEN licence_expiry_dt IS NULL OR licence_expiry_dt >= :asOf THEN 'Y' ELSE 'N' END \
            OR COALESCE(a_or_b_rego_fl, '?') <> CASE WHEN vehicle_usage_cd IN (10, 11) THEN 'Y' ELSE 'N' END \
            OR COALESCE(lapsed_fl, '?') <> CASE WHEN regt_status_cd = 5 THEN 'Y' ELSE 'N' END \
            OR COALESCE(exempt_fl, '?') <> CASE WHEN exempt_end_dt >= :asOf THEN 'Y' ELSE 'N' END""";

    static final String SORT_KEY = "vehicle_id";

    static final String UPDATE = """
            UPDATE vehicle_status \
            SET inspection_valid_fl = :inspectionFlag, licence_valid_fl = :licenceFlag, \
            a_or_b_rego_fl = :aOrBRegoFlag, lapsed_fl = :lapsedFlag, exempt_fl = :exemptFlag \
            WHERE vehicle_id = :vehicleId""";

    private ComplianceSql() {
    }
}
