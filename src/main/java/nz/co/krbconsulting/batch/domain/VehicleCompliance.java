package nz.co.krbconsulting.batch.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A vehicle alongside the status flags recorded for it. The flags drift from the vehicle as time passes (dates
 * expire) or if a change ever bypasses the trigger; this is where that is corrected.
 *
 * @param recorded the flags as stored, or null if any is missing (a new column not yet populated)
 */
public record VehicleCompliance(Vehicle vehicle, ComplianceFlags recorded) {

    /** The flags to store as of the given day, or empty if the recorded flags are already correct. */
    public Optional<ComplianceUpdate> reassessedOn(LocalDate day) {
        ComplianceFlags expected = vehicle.flagsOn(day);
        if (expected.equals(recorded)) {
            return Optional.empty();
        }
        return Optional.of(new ComplianceUpdate(vehicle.vehicleId(), expected));
    }
}
