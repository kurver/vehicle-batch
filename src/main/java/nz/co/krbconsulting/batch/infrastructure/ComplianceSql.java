package nz.co.krbconsulting.batch.infrastructure;

/**
 * Legacy schema names and queries, kept in one place. Plain ANSI SQL so it runs on DB2 and Postgres.
 *
 * <p>Time alone can only flip a flag from Y to N (a date passes); N to Y needs the vehicle to change, which the
 * database trigger handles. So the job only selects rows that are still Y but whose expiry has passed, which
 * keeps the work proportional to what changed rather than to the 11M rows.
 */
final class ComplianceSql {

    static final String SELECT = "vehicle_id, inspection_expiry_dt, licence_expiry_dt, inspection_valid_fl, licence_valid_fl";

    /**
     * A derived table so the paging key {@code vehicle_id} is unambiguous across the two tables; both databases
     * merge it into the outer query, so the predicates still reach the base tables' indexes.
     */
    static final String FROM = """
            (SELECT v.vehicle_id, v.inspection_expiry_dt, v.licence_expiry_dt, \
            s.inspection_valid_fl, s.licence_valid_fl \
            FROM vehicle v JOIN vehicle_status s ON s.vehicle_id = v.vehicle_id) c""";

    static final String WHERE = """
            (inspection_valid_fl = 'Y' AND inspection_expiry_dt < :asOf) \
            OR (licence_valid_fl = 'Y' AND licence_expiry_dt < :asOf)""";

    static final String SORT_KEY = "vehicle_id";

    static final String UPDATE = """
            UPDATE vehicle_status \
            SET inspection_valid_fl = :inspectionFlag, licence_valid_fl = :licenceFlag \
            WHERE vehicle_id = :vehicleId""";

    private ComplianceSql() {
    }
}
