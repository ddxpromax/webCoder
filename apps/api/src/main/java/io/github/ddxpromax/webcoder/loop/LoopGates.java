package io.github.ddxpromax.webcoder.loop;

import java.util.List;

/** Majo's built-in loop boundary gates, kept deterministic and session-safe. */
public final class LoopGates {
    private LoopGates() { }
    public static LoopDecision afterTurn(LoopSessionManager.Session session, String text, int turnTokens) {
        session.iteration++;
        session.totalTokens += Math.max(0, turnTokens);
        if (session.iteration >= session.maxIterations) return LoopDecision.stop("Iteration limit reached (" + session.maxIterations + ").");
        if (session.maxTokens > 0 && session.totalTokens >= session.maxTokens) return LoopDecision.stop("Token budget reached (" + session.maxTokens + ").");
        if (session.timeoutSeconds > 0 && System.nanoTime() - session.startedNanos >= session.timeoutSeconds * 1_000_000_000L) return LoopDecision.stop("Loop time limit reached (" + session.timeoutSeconds + "s).");
        if ("mission".equals(session.modeId)) return LoopDecision.stop("Mission turn completed.");
        if (!session.completionGate) return LoopDecision.stop("All configured loop gates bypassed.");
        String lowered = text == null ? "" : text.toLowerCase();
        if (session.completionSignals.stream().map(String::toLowerCase).anyMatch(lowered::contains)) return LoopDecision.stop("Completion signal detected.");
        if (session.iteration >= session.maxCompletionChecks) return LoopDecision.stop("Maximum completion checks reached (" + session.maxCompletionChecks + ").");
        return LoopDecision.continueWith("Continue working toward the goal. The previous response did not confirm completion. Inspect the current state and take the next necessary action.", "Goal not yet complete.");
    }
}
