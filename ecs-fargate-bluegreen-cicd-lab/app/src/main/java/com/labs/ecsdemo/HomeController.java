package com.labs.ecsdemo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    @Value("${app.owner-name}")
    private String ownerName;

    @Value("${app.lab-name}")
    private String labName;

    @Value("${app.version}")
    private String appVersion;

    private final RevisionLogService revisionLog;
    

    public HomeController(RevisionLogService revisionLog) {
        this.revisionLog = revisionLog;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("ownerName", ownerName);
        model.addAttribute("labName", labName);
        model.addAttribute("appVersion", appVersion);
        model.addAttribute("hostname", resolveHostname());
        model.addAttribute("revisions", revisionLog.entries());
        return "index";
    }

    @PostMapping("/revisions")
    public String addRevision(@RequestParam String author, @RequestParam String note) {
        revisionLog.add(author, note);
        return "redirect:/";
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
