package nz.co.krbconsulting.batch.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A vehicle's recorded inspection and licence validity alongside the expiry dates they derive from.
 * The flags go stale as time passes without the vehicle changing; this is where that is corrected.
 */
public record VehicleCompliance(
        long vehicleId,
        Expiry inspectionExpiry,
        Expiry licenceExpiry,
        boolean inspectionValid,
        boolean licenceValid) {

    /** The state on the given day, or empty if the recorded flags are already correct. */
    public Optional<VehicleCompliance> reassessedOn(LocalDate day) {
        boolean inspection = inspectionExpiry.isValidOn(day);
        boolean licence = licenceExpiry.isValidOn(day);
        if (inspection == inspectionValid && licence == licenceValid) {
            return Optional.empty();
        }
        return Optional.of(new VehicleCompliance(vehicleId, inspectionExpiry, licenceExpiry, inspection, licence));
    }
}
