package nz.co.krbconsulting.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VehicleComplianceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Test
    void validThroughTheExpiryDate() {
        assertThat(new Expiry(DAY).isValidOn(DAY)).isTrue();
        assertThat(new Expiry(DAY).isValidOn(DAY.plusDays(1))).isFalse();
    }

    @Test
    void missingExpiryIsAlwaysValid() {
        assertThat(new Expiry(null).isValidOn(DAY)).isTrue();
    }

    @Test
    void exemptThroughTheEndDateAndNotExemptWithoutOne() {
        assertThat(new Exemption(DAY).isExemptOn(DAY)).isTrue();
        assertThat(new Exemption(DAY).isExemptOn(DAY.plusDays(1))).isFalse();
        assertThat(new Exemption(null).isExemptOn(DAY)).isFalse();
    }

    @Test
    void usageTenAndElevenAreAOrBRego() {
        assertThat(new VehicleUsage(10).isAOrBRego()).isTrue();
        assertThat(new VehicleUsage(11).isAOrBRego()).isTrue();
        assertThat(new VehicleUsage(12).isAOrBRego()).isFalse();
        assertThat(new VehicleUsage(null).isAOrBRego()).isFalse();
    }

    @Test
    void onlyStatusFiveIsLapsed() {
        assertThat(new RegistrationStatus(RegistrationStatus.LAPSED).isLapsed()).isTrue();
        assertThat(new RegistrationStatus(RegistrationStatus.ACTIVE).isLapsed()).isFalse();
        assertThat(new RegistrationStatus(null).isLapsed()).isFalse();
    }

    @Test
    void flagsGoStaleOncePastADate() {
        var vehicle = new Vehicle(1, new Expiry(DAY), new Expiry(null), new VehicleUsage(10),
                new RegistrationStatus(1), new Exemption(DAY));
        var recorded = new ComplianceFlags(true, true, true, false, true);
        var compliance = new VehicleCompliance(vehicle, recorded);

        assertThat(compliance.reassessedOn(DAY)).isEmpty();
        assertThat(compliance.reassessedOn(DAY.plusDays(1)))
                .hasValue(new ComplianceUpdate(1, new ComplianceFlags(false, true, true, false, false)));
    }

    @Test
    void missingRecordedFlagsAreAlwaysRewritten() {
        var vehicle = new Vehicle(1, new Expiry(null), new Expiry(null), new VehicleUsage(null),
                new RegistrationStatus(null), new Exemption(null));

        assertThat(new VehicleCompliance(vehicle, null).reassessedOn(DAY))
                .hasValue(new ComplianceUpdate(1, new ComplianceFlags(true, true, false, false, false)));
    }
}
