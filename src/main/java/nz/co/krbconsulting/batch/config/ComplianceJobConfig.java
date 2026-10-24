package nz.co.krbconsulting.batch.config;

import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;

import nz.co.krbconsulting.batch.domain.ComplianceUpdate;
import nz.co.krbconsulting.batch.domain.VehicleCompliance;
import nz.co.krbconsulting.batch.infrastructure.ComplianceJdbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Brings the vehicle status flags (inspection, licence, A/B rego, lapsed, exempt) up to date as time passes.
 *
 * <p>Stateless: the job repository is Spring Batch's in-memory default, so nothing beyond vehicle_status is written
 * to the database. The work is idempotent (only still-stale rows are selected), so a failed run is recovered by
 * simply running again. {@code asOf=YYYY-MM-DD} pins the date, otherwise it is today.
 */
@Configuration
@EnableConfigurationProperties(ComplianceProperties.class)
public class ComplianceJobConfig {

    private static final Logger log = LoggerFactory.getLogger(ComplianceJobConfig.class);

    @Bean
    Clock clock() {
        // the image runs in Pacific/Auckland, matching the database's CURRENT_DATE that the trigger uses
        return Clock.systemDefaultZone();
    }

    @Bean
    Job complianceJob(JobRepository jobRepository, Step complianceStep) {
        return new JobBuilder("complianceJob", jobRepository)
                .start(complianceStep)
                .build();
    }

    @Bean
    Step complianceStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ComplianceProperties properties,
            JdbcPagingItemReader<VehicleCompliance> staleComplianceReader,
            ItemProcessor<VehicleCompliance, ComplianceUpdate> complianceProcessor,
            JdbcBatchItemWriter<ComplianceUpdate> complianceWriter) {
        return new StepBuilder("complianceStep", jobRepository)
                .<VehicleCompliance, ComplianceUpdate>chunk(properties.chunkSize())
                // one short database transaction per chunk
                .transactionManager(transactionManager)
                .reader(staleComplianceReader)
                .processor(complianceProcessor)
                .writer(complianceWriter)
                .listener(new StepExecutionListener() {
                    @Override
                    public ExitStatus afterStep(StepExecution step) {
                        log.info("Compliance run {}: read {}, updated {}, unchanged {}, commits {}",
                                step.getExitStatus().getExitCode(), step.getReadCount(), step.getWriteCount(),
                                step.getFilterCount(), step.getCommitCount());
                        return step.getExitStatus();
                    }
                })
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<VehicleCompliance> staleComplianceReader(
            DataSource dataSource,
            ComplianceProperties properties,
            @Value("#{jobParameters['asOf']}") String asOf,
            Clock clock) throws Exception {
        return ComplianceJdbc.staleComplianceReader(dataSource, resolve(asOf, clock), properties.chunkSize());
    }

    @Bean
    @StepScope
    ComplianceProcessor complianceProcessor(@Value("#{jobParameters['asOf']}") String asOf, Clock clock) {
        LocalDate day = resolve(asOf, clock);
        log.info("Reassessing vehicle compliance as of {}", day);
        return new ComplianceProcessor(day);
    }

    @Bean
    JdbcBatchItemWriter<ComplianceUpdate> complianceWriter(DataSource dataSource) {
        return ComplianceJdbc.complianceWriter(dataSource);
    }

    private static LocalDate resolve(String asOf, Clock clock) {
        return asOf == null ? LocalDate.now(clock) : LocalDate.parse(asOf);
    }
}
