package com.example.ebapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates a real-world backend dependency: on every call this
 * writes a small heartbeat record to a DynamoDB table and confirms the
 * table is reachable, proving live connectivity rather than a mocked
 * response. Table name and region are supplied via Elastic Beanstalk
 * environment variables, never hard-coded.
 */
@Service
public class DynamoDbService {

    private final DynamoDbClient dynamoDbClient;

    @Value("${app.dynamodb.table-name}")
    private String tableName;

    public DynamoDbService(@Lazy DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public Map<String, Object> checkConnectivity() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (tableName == null || tableName.isBlank()) {
            result.put("status", "SKIPPED");
            result.put("message", "DYNAMODB_TABLE_NAME environment variable is not set.");
            return result;
        }

        try {
            DescribeTableResponse describe = dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());

            String requestId = UUID.randomUUID().toString();
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "id", AttributeValue.builder().s(requestId).build(),
                            "source", AttributeValue.builder().s("elastic-beanstalk-app").build(),
                            "timestamp", AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build());

            result.put("status", "CONNECTED");
            result.put("table", tableName);
            result.put("tableStatus", describe.table().tableStatusAsString());
            result.put("heartbeatId", requestId);
        } catch (DynamoDbException e) {
            result.put("status", "ERROR");
            result.put("table", tableName);
            result.put("message", e.awsErrorDetails() != null
                    ? e.awsErrorDetails().errorMessage()
                    : e.getMessage());
        }

        return result;
    }
}