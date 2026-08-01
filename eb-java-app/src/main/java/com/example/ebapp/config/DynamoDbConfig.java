package com.example.ebapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Builds the DynamoDB client used for the external service integration
 * (optional challenge). Credentials are resolved automatically via the
 * Elastic Beanstalk instance profile (no keys stored in the app or repo);
 * only the region and table name come from environment variables.
 */
@Configuration
public class DynamoDbConfig {

    @Value("${app.aws.region}")
    private String region;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .build();
    }
}
