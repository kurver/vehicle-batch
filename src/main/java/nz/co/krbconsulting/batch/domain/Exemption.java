package nz.co.krbconsulting.batch.domain;

import java.time.LocalDate;

/**
 * An exemption from registration, in force through its end date. No end date means the vehicle is not exempt; note
 * this is the opposite of {@link Expiry}, where no date means always valid.
 */
public record Exemption(LocalDate endDate) {

    public boolean isExemptOn(LocalDate day) {
        return endDate != null && !day.isAfter(endDate);
    }
}
