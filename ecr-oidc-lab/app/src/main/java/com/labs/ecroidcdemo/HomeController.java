package com.labs.ecroidcdemo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
                "message", "Hello from a securely containerized Java (Spring Boot) app!",
                "hostname", resolveHostname());
    }

    // Used by the Docker HEALTHCHECK instruction and by container orchestrators.
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
