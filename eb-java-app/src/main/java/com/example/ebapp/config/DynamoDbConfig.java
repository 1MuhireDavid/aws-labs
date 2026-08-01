package com.example.ebapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Builds the DynamoDB client used for the external service integration
 * (optional challenge). Credentials are resolved automatically via the
 * Elastic Beanstalk instance profile (no keys stored in the app or repo);
 * only the region and table name come from environment variables.
 *
 * The client bean is @Lazy so it is only created when first used (an actual
 * /db-check request), not at application startup. This keeps the app (and the
 * Spring context-load test) from failing in environments where AWS_REGION is
 * not configured, e.g. the CI build runner.
 */
@Configuration
public class DynamoDbConfig {

    // Falls back to eu-north-1 if the property is missing OR blank.
    @Value("${app.aws.region:eu-north-1}")
    private String region;

    @Bean
    @Lazy
    public DynamoDbClient dynamoDbClient() {
        String resolvedRegion = StringUtils.hasText(region) ? region : "eu-north-1";
        return DynamoDbClient.builder()
                .region(Region.of(resolvedRegion))
                .build();
    }
}