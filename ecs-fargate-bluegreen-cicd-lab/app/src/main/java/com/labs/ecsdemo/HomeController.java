package com.labs.ecsdemo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @Value("${app.owner-name}")
    private String ownerName;

    @Value("${app.lab-name}")
    private String labName;

    @Value("${app.version}")
    private String appVersion;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("ownerName", ownerName);
        model.addAttribute("labName", labName);
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("hostname", resolveHostname());
        return "index";
    }

    // Surfacing the container's own hostname (== ECS task ENI id) makes a
    // blue/green traffic shift visible on refresh during a deployment.
    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
