package nz.co.krbconsulting.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CompliancePropertiesTest {

    @EnableConfigurationProperties(ComplianceProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void chunkSizeDefaultsTo1000() {
        context.run(ctx -> assertThat(ctx.getBean(ComplianceProperties.class).chunkSize()).isEqualTo(1000));
    }

    @Test
    void chunkSizeBelowOneStopsStartup() {
        context.withPropertyValues("compliance.chunk-size=0").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessage("compliance.chunk-size must be at least 1, was 0"));
    }
}
