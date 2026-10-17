package io.github.ddxpromax.webcoder.mcp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class McpController {
    private final McpService service;
    private final McpRuntime runtime;
    private final McpOAuthService oauth;

    public McpController(McpService service, McpRuntime runtime) {
        this.service = service;
        this.runtime = runtime;
        this.oauth = new McpOAuthService(service);
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

    @PostMapping({
        "/mcp/oauth/start/{clientKey}",
        "/agents/{agentId}/mcp/oauth/start/{clientKey}"
    })
    public McpModels.OAuthStartResponse startOAuth(
        @PathVariable String clientKey,
        @RequestBody(required = false) McpModels.OAuthStartRequest request,
        HttpServletRequest servletRequest
    ) {
        return oauth.start(clientKey, request, McpOAuthService.redirectUri(
            servletRequest.getScheme(), servletRequest.getServerName(),
            servletRequest.getServerPort()
        ));
    }

    @GetMapping({
        "/mcp/oauth/status/{clientKey}",
        "/agents/{agentId}/mcp/oauth/status/{clientKey}"
    })
    public McpModels.OAuthStatus oauthStatus(
        @PathVariable String clientKey
    ) {
        return service.oauthStatus(clientKey);
    }

    @DeleteMapping({
        "/mcp/oauth/{clientKey}",
        "/agents/{agentId}/mcp/oauth/{clientKey}"
    })
    public McpModels.Message revokeOAuth(@PathVariable String clientKey) {
        return oauth.revoke(clientKey);
    }

    @GetMapping(value = "/mcp/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String oauthCallback(
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        @RequestParam(value = "error_description", required = false) String errorDescription
    ) {
        return oauth.callback(code, state, error, errorDescription);
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
