package io.github.ddxpromax.webcoder.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval")
@CrossOrigin(origins = "*")
public class ApprovalController {
    private final ApprovalStore store;

    public ApprovalController(ApprovalStore store) {
        this.store = store;
    }

    @GetMapping("/list")
    public Map<String, Object> list(
        @RequestParam(required = false) String session_id
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApprovalStore.ApprovalRequest request
            : store.listPending()) {
            if (session_id != null && !session_id.isBlank()
                && !session_id.equals(request.rootSessionId)
                && !session_id.equals(request.sessionId)) {
                continue;
            }
            result.add(toResponse(request));
        }
        return Map.of(
            "pending_approvals", result,
            "count", result.size()
        );
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(
        @RequestBody Map<String, Object> body
    ) {
        return decide(body, true);
    }

    @PostMapping("/deny")
    public ResponseEntity<Map<String, Object>> deny(
        @RequestBody Map<String, Object> body
    ) {
        return decide(body, false);
    }

    private ResponseEntity<Map<String, Object>> decide(
        Map<String, Object> body,
        boolean approved
    ) {
        String requestId = text(body.get("request_id"));
        String sessionId = text(body.get("session_id"));
        if (requestId.isBlank() || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "detail", "request_id and session_id are required"
            ));
        }
        ApprovalStore.ApprovalRequest request =
            store.get(requestId);
        if (request == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail",
                    "Approval request not found: "
                        + shortId(requestId)
                ));
        }
        if (!request.rootSessionId.equals(sessionId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("detail", "Root session mismatch: "
                    + "cannot decide other session trees"
                ));
        }
        String scope = text(body.get("scope")).trim()
            .toLowerCase();
        String reason = approved
            ? ("similar".equals(scope) ? "similar" : "exact")
            : text(body.getOrDefault("reason", "User denied"));
        ApprovalStore.ApprovalRequest resolved = store.resolve(
            requestId,
            approved ? "approved" : "denied",
            reason
        );
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail",
                    "Approval request not found: "
                        + shortId(requestId)
                ));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", approved
            ? "Tool '" + request.toolName
                + "' approved, executing..."
            : "Tool '" + request.toolName + "' denied: "
                + reason
        );
        result.put("tool_name", request.toolName);
        result.put("request_id", requestId);
        return ResponseEntity.ok(result);
    }

    public static Map<String, Object> toResponse(
        ApprovalStore.ApprovalRequest request
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("request_id", request.requestId);
        item.put("session_id", request.sessionId);
        item.put("root_session_id", request.rootSessionId);
        item.put("owner_agent_id", request.agentId);
        item.put("agent_id", request.agentId);
        item.put("tool_name", request.toolName);
        item.put("tool_display_name", request.toolDisplayName);
        item.put("severity", request.severity);
        item.put("findings_count", 0);
        item.put("created_at", Instant.ofEpochMilli(
            request.createdAt
        ).toString());
        item.put("timeout_seconds", request.timeoutSeconds);
        item.put("result_summary", null);
        return item;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String shortId(String id) {
        return id.length() <= 16 ? id : id.substring(0, 16);
    }
}
