package io.github.ddxpromax.webcoder.loop;

/** Majo loop gate decision for the boundary after one completed agent turn. */
public record LoopDecision(boolean continueLoop, String continuation, String reason) {
    public static LoopDecision stop(String reason) { return new LoopDecision(false, "", reason); }
    public static LoopDecision continueWith(String prompt, String reason) { return new LoopDecision(true, prompt, reason); }
}
