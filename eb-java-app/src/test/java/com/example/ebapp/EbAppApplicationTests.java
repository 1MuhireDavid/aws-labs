package com.example.ebapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EbAppApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context (including DynamoDbClient bean) wires up cleanly.
    }
}
