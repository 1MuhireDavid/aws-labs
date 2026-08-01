package com.example.ebapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Elastic Beanstalk demo application.
 *
 * Packaged as an executable jar and run under the Elastic Beanstalk
 * "Java SE" platform via the Procfile (java -jar application.jar).
 * Elastic Beanstalk sets the PORT environment variable; Spring Boot
 * picks it up through server.port=${PORT:5000} in application.properties.
 */
@SpringBootApplication
public class EbAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(EbAppApplication.class, args);
    }
}
