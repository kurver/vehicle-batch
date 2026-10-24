package nz.co.krbconsulting.batch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param chunkSize rows read and updated per transaction */
@ConfigurationProperties("compliance")
public record ComplianceProperties(@DefaultValue("1000") int chunkSize) {

    /** Checked here so a bad setting stops the job at startup, before it touches the database. */
    public ComplianceProperties {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("compliance.chunk-size must be at least 1, was " + chunkSize);
        }
    }
}
