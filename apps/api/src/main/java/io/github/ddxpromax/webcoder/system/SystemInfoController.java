package io.github.ddxpromax.webcoder.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private final String applicationName;

    public SystemInfoController(
        @Value("${spring.application.name}") String applicationName) {
            this.applicationName = applicationName;
        }
    
    @GetMapping("/info")
    public SystemInfoResponse getInfo() {
        return new SystemInfoResponse(applicationName);
    }
}
