package nz.co.krbconsulting.batch.domain;

/** How a vehicle is used. Codes 10 and 11 are the farm vehicles registered as "A or B". */
public record VehicleUsage(Integer code) {

    public boolean isAOrBRego() {
        return code != null && (code == 10 || code == 11);
    }
}
