package io.github.ddxpromax.webcoder.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ApprovalStore {
    public static final class ApprovalRequest {
        public final String requestId;
        public final String sessionId;
        public final String rootSessionId;
        public final String agentId;
        public final String toolName;
        public final String toolDisplayName;
        public final String severity;
        public final long createdAt;
        public final long timeoutSeconds;
        public final CompletableFuture<String> future =
            new CompletableFuture<>();
        public volatile boolean resolved;
        public volatile String decision;
        public volatile String reason;

        private ApprovalRequest(
            String requestId,
            String sessionId,
            String rootSessionId,
            String agentId,
            String toolName,
            String toolDisplayName,
            String severity,
            long timeoutSeconds
        ) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.rootSessionId = rootSessionId;
            this.agentId = agentId;
            this.toolName = toolName;
            this.toolDisplayName = toolDisplayName;
            this.severity = severity;
            this.createdAt = System.currentTimeMillis();
            this.timeoutSeconds = timeoutSeconds;
        }

        public String await(long timeoutMillis) {
            try {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "denied";
            } catch (Exception exception) {
                return "denied";
            }
        }
    }

    private final ConcurrentHashMap<String, ApprovalRequest> pending =
        new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ApprovalRequest> history =
        new CopyOnWriteArrayList<>();

    public ApprovalRequest register(
        String sessionId,
        String rootSessionId,
        String agentId,
        String toolName,
        String toolDisplayName,
        String severity,
        long timeoutSeconds
    ) {
        ApprovalRequest request = new ApprovalRequest(
            java.util.UUID.randomUUID().toString(), sessionId,
            rootSessionId, agentId, toolName, toolDisplayName,
            severity, timeoutSeconds
        );
        pending.put(request.requestId, request);
        history.add(request);
        return request;
    }

    public ApprovalRequest get(String requestId) {
        return pending.get(requestId);
    }

    public List<ApprovalRequest> listPending() {
        return pending.values().stream()
            .filter(request -> !request.resolved)
            .sorted((left, right) -> Long.compare(
                right.createdAt, left.createdAt
            )).toList();
    }

    public List<ApprovalRequest> listHistory() {
        return List.copyOf(history);
    }

    public ApprovalRequest resolve(
        String requestId,
        String decision,
        String reason
    ) {
        ApprovalRequest request = pending.remove(requestId);
        if (request == null) {
            return null;
        }
        request.resolved = true;
        request.decision = decision;
        request.reason = reason;
        request.future.complete("approved".equals(decision)
            ? "approved" : "denied");
        return request;
    }

    public void cancelSession(String sessionId) {
        for (ApprovalRequest request : listPending()) {
            if (sessionId.equals(request.rootSessionId)
                || sessionId.equals(request.sessionId)) {
                resolve(request.requestId, "denied", "session ended");
            }
        }
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        for (ApprovalRequest request : listPending()) {
            if (request.timeoutSeconds > 0
                && now - request.createdAt
                    > request.timeoutSeconds * 1000) {
                resolve(request.requestId, "denied", "timeout");
            }
        }
    }
}
