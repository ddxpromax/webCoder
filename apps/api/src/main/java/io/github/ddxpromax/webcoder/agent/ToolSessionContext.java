package io.github.ddxpromax.webcoder.agent;

/** Per-tool-thread chat identity for tools that surface interactive approvals. */
public final class ToolSessionContext {
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();
    private ToolSessionContext() { }
    public static void set(String sessionId, String agentId) { SESSION.set(sessionId); AGENT.set(agentId); }
    public static String sessionId() { return SESSION.get() == null ? "" : SESSION.get(); }
    public static String agentId() { return AGENT.get() == null ? "default" : AGENT.get(); }
    public static void clear() { SESSION.remove(); AGENT.remove(); }
}
