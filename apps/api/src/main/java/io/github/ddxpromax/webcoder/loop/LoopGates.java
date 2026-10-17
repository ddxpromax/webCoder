package io.github.ddxpromax.webcoder.loop;

import java.util.List;

/** Majo's built-in loop boundary gates, kept deterministic and session-safe. */
public final class LoopGates {
    private static final List<String> COMPLETE = List.of("completed", "done", "finished");
    private LoopGates() { }
    public static LoopDecision afterTurn(LoopSessionManager.Session session, String text, int turnTokens) {
        session.iteration++;
        session.totalTokens += Math.max(0, turnTokens);
        if (session.iteration >= session.maxIterations) return LoopDecision.stop("Iteration limit reached (" + session.maxIterations + ").");
        if (session.maxTokens > 0 && session.totalTokens >= session.maxTokens) return LoopDecision.stop("Token budget reached (" + session.maxTokens + ").");
        if ("mission".equals(session.modeId)) return LoopDecision.stop("Mission turn completed.");
        String lowered = text == null ? "" : text.toLowerCase();
        if (COMPLETE.stream().anyMatch(lowered::contains)) return LoopDecision.stop("Completion signal detected.");
        if (session.iteration >= 10) return LoopDecision.stop("Maximum completion checks reached (10).");
        return LoopDecision.continueWith("Continue working toward the goal. The previous response did not confirm completion. Inspect the current state and take the next necessary action.", "Goal not yet complete.");
    }
}
