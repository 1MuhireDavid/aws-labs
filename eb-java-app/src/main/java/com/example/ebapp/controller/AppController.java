package com.example.ebapp.controller;

import com.example.ebapp.service.DynamoDbService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public endpoints demonstrating a successful deployment, exposing
 * version/build metadata for CI/CD validation, and (optionally)
 * proving connectivity to an external data service (DynamoDB).
 */
@RestController
public class AppController {

    private final DynamoDbService dynamoDbService;

    // Injected from the application jar's build metadata (Maven resource filtering)
    @Value("${app.build.version}")
    private String appVersion;

    // Injected as Elastic Beanstalk environment variables set by the
    // GitHub Actions workflow on every deployment, so /version reflects
    // exactly which commit and build produced the running application.
    @Value("${APP_COMMIT:unknown}")
    private String commitSha;

    @Value("${APP_BUILD_TIME:unknown}")
    private String buildTime;

    public AppController(DynamoDbService dynamoDbService) {
        this.dynamoDbService = dynamoDbService;
    }

    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Deployment successful! Java app on Elastic Beanstalk - v2 (CI/CD demo).");
        response.put("version", appVersion);
        response.put("commit", commitSha);
        response.put("timestamp", Instant.now().toString());
        return response;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", appVersion);
        response.put("commit", commitSha);
        response.put("buildTime", buildTime);
        return response;
    }

    @GetMapping("/db-check")
    public Map<String, Object> dbCheck() {
        return dynamoDbService.checkConnectivity();
    }
}
