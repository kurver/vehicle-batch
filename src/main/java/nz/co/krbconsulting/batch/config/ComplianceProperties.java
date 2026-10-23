package nz.co.krbconsulting.batch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param chunkSize rows read and updated per transaction */
@ConfigurationProperties("compliance")
public record ComplianceProperties(@DefaultValue("1000") int chunkSize) {
}
