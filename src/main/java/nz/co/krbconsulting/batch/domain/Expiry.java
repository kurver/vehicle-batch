package nz.co.krbconsulting.batch.domain;

import java.time.LocalDate;

/**
 * An expiry date for an inspection or licence. A missing date means there is nothing to expire,
 * so it is always valid. Otherwise it is valid through the expiry date itself.
 */
public record Expiry(LocalDate date) {

    public boolean isValidOn(LocalDate day) {
        return date == null || !day.isAfter(date);
    }
}
