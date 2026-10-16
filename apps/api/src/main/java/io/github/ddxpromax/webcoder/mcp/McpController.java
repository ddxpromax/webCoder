package io.github.ddxpromax.webcoder.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class McpController {
    private final McpService service;
    private final McpRuntime runtime;

    public McpController(McpService service, McpRuntime runtime) {
        this.service = service;
        this.runtime = runtime;
    }

    @GetMapping({"/mcp", "/agents/{agentId}/mcp"})
    public List<McpModels.ClientInfo> list() {
        return service.list();
    }

    @GetMapping({
        "/mcp/{clientKey}",
        "/agents/{agentId}/mcp/{clientKey}"
    })
    public McpModels.ClientInfo get(
        @PathVariable String clientKey
    ) {
        return service.get(clientKey);
    }

    @PostMapping({"/mcp", "/agents/{agentId}/mcp"})
    public McpModels.ClientInfo create(
        @RequestBody McpModels.CreateRequest request
    ) {
        if (request == null || request.clientKey() == null
            || request.clientKey().isBlank()) {
            throw new IllegalArgumentException(
                "Field 'client_key' is required"
            );
        }
        return service.create(request.clientKey().trim(),
            request.client());
    }

    @PutMapping({
        "/mcp/{clientKey}",
        "/agents/{agentId}/mcp/{clientKey}"
    })
    public McpModels.ClientInfo update(
        @PathVariable String clientKey,
        @RequestBody McpModels.ClientData changes
    ) {
        return service.update(clientKey, changes);
    }

    @PatchMapping({
        "/mcp/toggle/{clientKey}",
        "/agents/{agentId}/mcp/toggle/{clientKey}"
    })
    public McpModels.ClientInfo toggle(
        @PathVariable String clientKey
    ) {
        return service.toggle(clientKey);
    }

    @DeleteMapping({
        "/mcp/{clientKey}",
        "/agents/{agentId}/mcp/{clientKey}"
    })
    public McpModels.Message delete(
        @PathVariable String clientKey
    ) {
        return service.delete(clientKey);
    }

    @GetMapping({
        "/mcp/tools/{clientKey}",
        "/agents/{agentId}/mcp/tools/{clientKey}"
    })
    public List<McpModels.ToolInfo> tools(
        @PathVariable String clientKey
    ) {
        return runtime.listTools(clientKey);
    }

    @PutMapping({
        "/mcp/tools/{clientKey}",
        "/agents/{agentId}/mcp/tools/{clientKey}"
    })
    public List<McpModels.ToolInfo> updateTools(
        @PathVariable String clientKey,
        @RequestBody(required = false)
        McpModels.ToolWhitelistRequest request
    ) {
        service.setToolWhitelist(clientKey,
            request == null ? null : request.tools());
        return runtime.listTools(clientKey);
    }

    @GetMapping({
        "/mcp/policy/{clientKey}",
        "/agents/{agentId}/mcp/policy/{clientKey}"
    })
    public McpModels.AccessPolicy policy(
        @PathVariable String clientKey
    ) {
        return service.policy(clientKey);
    }

    @PutMapping({
        "/mcp/policy/{clientKey}",
        "/agents/{agentId}/mcp/policy/{clientKey}"
    })
    public McpModels.AccessPolicy updatePolicy(
        @PathVariable String clientKey,
        @RequestBody McpModels.AccessPolicy policy
    ) {
        return service.updatePolicy(clientKey, policy);
    }

    @GetMapping({
        "/mcp/access-principals",
        "/agents/{agentId}/mcp/access-principals"
    })
    public List<McpModels.AccessPrincipalOption>
        accessPrincipals() {
        return service.accessPrincipals();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(
        IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(Map.of(
            "detail", exception.getMessage()
        ));
    }

    @ExceptionHandler(McpNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(
        McpNotFoundException exception
    ) {
        return ResponseEntity.status(404).body(Map.of(
            "detail", exception.getMessage()
        ));
    }

    @ExceptionHandler(McpUpstreamException.class)
    public ResponseEntity<Map<String, String>> upstream(
        McpUpstreamException exception
    ) {
        return ResponseEntity.status(502).body(Map.of(
            "detail", exception.getMessage()
        ));
    }
}
