package nz.co.krbconsulting.batch.domain;

/** The derived Y/N status flags held in vehicle_status. */
public record ComplianceFlags(
        boolean inspectionValid,
        boolean licenceValid,
        boolean aOrBRego,
        boolean lapsed,
        boolean exempt) {
}
