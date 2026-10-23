package nz.co.krbconsulting.batch.domain;

import java.time.LocalDate;

/** The facts about a vehicle that its status flags are derived from. */
public record Vehicle(
        long vehicleId,
        Expiry inspectionExpiry,
        Expiry licenceExpiry,
        VehicleUsage usage,
        RegistrationStatus registrationStatus,
        Exemption exemption) {

    /** What the flags should be on the given day. */
    public ComplianceFlags flagsOn(LocalDate day) {
        return new ComplianceFlags(
                inspectionExpiry.isValidOn(day),
                licenceExpiry.isValidOn(day),
                usage.isAOrBRego(),
                registrationStatus.isLapsed(),
                exemption.isExemptOn(day));
    }
}
