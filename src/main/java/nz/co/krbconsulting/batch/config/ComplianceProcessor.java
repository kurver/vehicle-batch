package nz.co.krbconsulting.batch.config;

import java.time.LocalDate;

import nz.co.krbconsulting.batch.domain.ComplianceUpdate;
import nz.co.krbconsulting.batch.domain.VehicleCompliance;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Reassesses each vehicle as of a fixed day, filtering out those whose flags are already current.
 *
 * <p>A concrete, non-final class so the {@code @StepScope} proxy can subclass it and Spring Batch can inspect it for
 * listener annotations.
 */
class ComplianceProcessor implements ItemProcessor<VehicleCompliance, ComplianceUpdate> {

    private final LocalDate day;

    ComplianceProcessor(LocalDate day) {
        this.day = day;
    }

    @Override
    public ComplianceUpdate process(VehicleCompliance compliance) {
        return compliance.reassessedOn(day).orElse(null);
    }
}
