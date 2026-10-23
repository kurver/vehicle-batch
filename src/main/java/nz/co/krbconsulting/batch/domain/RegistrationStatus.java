package nz.co.krbconsulting.batch.domain;

/** The vehicle's registration status code: 1 is active, 5 is lapsed. */
public record RegistrationStatus(Integer code) {

    public static final int ACTIVE = 1;
    public static final int LAPSED = 5;

    public boolean isLapsed() {
        return code != null && code == LAPSED;
    }
}
