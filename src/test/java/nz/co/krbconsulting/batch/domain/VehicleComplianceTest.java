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
    void flagsGoStaleOncePastExpiry() {
        var vehicle = new VehicleCompliance(1, new Expiry(DAY), new Expiry(null), true, true);

        assertThat(vehicle.reassessedOn(DAY)).isEmpty();
        assertThat(vehicle.reassessedOn(DAY.plusDays(1)))
                .hasValueSatisfying(v -> {
                    assertThat(v.inspectionValid()).isFalse();
                    assertThat(v.licenceValid()).isTrue();
                });
    }
}
