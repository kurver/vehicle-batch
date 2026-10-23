package nz.co.krbconsulting.batch.domain;

/** Corrected flags to store for a vehicle. */
public record ComplianceUpdate(long vehicleId, ComplianceFlags flags) {
}
