package io.github.ddxpromax.webcoder.agent;

import io.github.ddxpromax.webcoder.model.ProviderModelRepository;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Strips media blocks (image / audio / video / PDF data) from the model
 * request when the active model does not support multimodal input. Ported
 * from QwenPaw's {@code message_request_normalizer}, including the
 * application/pdf handling (#7621): a text-only model receiving media
 * blocks fails the whole request on most providers.
 *
 * <p>Runs on PRE_REASONING so only the outgoing request is affected — the
 * stored conversation history keeps its media blocks. When the model's
 * capability cannot be resolved the hook fails open (no stripping).
 */
public class ModelRequestNormalizerHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ModelRequestNormalizerHook.class);

    /** Mirrors QwenPaw MEDIA_UNSUPPORTED_PLACEHOLDER. */
    public static final String MEDIA_UNSUPPORTED_PLACEHOLDER =
            "[Media content removed - model does not support this media type]";

    /** Document MIME types treated as media alongside image/audio/video. */
    private static final List<String> DOCUMENT_MIME_TYPES = List.of("application/pdf");

    private final String agentId;
    private final ModelRoutingService modelRoutingService;
    private final ProviderModelRepository providerModelRepo;

    public ModelRequestNormalizerHook(String agentId,
                                      ModelRoutingService modelRoutingService,
                                      ProviderModelRepository providerModelRepo) {
        this.agentId = agentId;
        this.modelRoutingService = modelRoutingService;
        this.providerModelRepo = providerModelRepo;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent pre)) {
            return Mono.just(event);
        }
        try {
            String agentId = this.agentId;
            boolean multimodal;
            try {
                multimodal = supportsMultimodal(agentId);
            } catch (Exception e) {
                multimodal = true; // capability unknown → fail open
            }
            List<Msg> msgs = pre.getInputMessages();
            if (msgs == null || msgs.isEmpty()) {
                return Mono.just(event);
            }

            // 1) Bound oversized tool results (always; request copies only).
            List<Msg> rebuilt = new ArrayList<>(msgs.size());
            boolean changed = false;
            for (Msg msg : msgs) {
                Msg bounded = ContextCompactor.boundToolResults(msg);
                if (bounded != msg) {
                    changed = true;
                }
                rebuilt.add(bounded);
            }

            // 1.5) PDF document blocks nested in tool results are stripped
            //      unconditionally: OpenAI-compatible chat-completions
            //      servers (vLLM, DeepSeek, DashScope, ...) reject "file"
            //      parts even on multimodal models, so the formatter path
            //      must never see them (QwenPaw #7636). User-supplied
            //      document blocks keep the multimodal-conditional path
            //      below; images/audio/video are preserved either way.
            List<Msg> docStripped = new ArrayList<>(rebuilt.size());
            for (Msg msg : rebuilt) {
                Msg cleaned = stripToolResultDocuments(msg);
                if (cleaned != msg) {
                    changed = true;
                }
                docStripped.add(cleaned);
            }
            rebuilt = docStripped;

            // 1.6) Drop empty assistant text blocks (QwenPaw #7409): a
            //      reasoning-only turn persists as {"type":"text","text":""}
            //      and some providers (Volcengine Ark) reject its replay
            //      with 400 — one empty turn would poison the session.
            List<Msg> emptyDropped = new ArrayList<>(rebuilt.size());
            boolean droppedEmpty = false;
            for (Msg msg : rebuilt) {
                Msg cleaned = dropEmptyAssistantTextBlocks(msg);
                if (cleaned == null) {
                    droppedEmpty = true;
                    continue;
                }
                if (cleaned != msg) {
                    changed = true;
                }
                emptyDropped.add(cleaned);
            }
            if (droppedEmpty) {
                changed = true;
                rebuilt = emptyDropped;
            }

            // 2) Media stripping for text-only models.
            if (!multimodal) {
                List<Msg> stripped = new ArrayList<>(rebuilt.size());
                for (Msg msg : rebuilt) {
                    Msg s = stripMediaBlocks(msg);
                    if (s != msg) {
                        changed = true;
                    }
                    stripped.add(s);
                }
                rebuilt = stripped;
            }

            // 3) Context-pressure compaction: fold old thinking and images
            //    when the request estimate exceeds the pressure threshold
            //    (QwenPaw #7521 thinking fold + #6456 visual compact).
            Integer window = resolveContextWindow(agentId);
            if (window != null && window > 0) {
                long threshold = (long) (window * ContextCompactor.PRESSURE_RATIO);
                long estimated = ContextCompactor.estimateTokens(rebuilt);
                if (estimated > threshold) {
                    List<Msg> folded = ContextCompactor.foldForPressure(rebuilt);
                    if (folded != rebuilt) {
                        changed = true;
                        rebuilt = folded;
                        log.info("[normalizer] context pressure: ~{} tokens > {} "
                                        + "(70% of {} window); folded old thinking/images",
                                estimated, threshold, window);
                    }
                }
            }

            if (changed) {
                pre.setInputMessages(rebuilt);
            }

        } catch (Exception e) {
            // Fail open — a broken compaction must never break a call.
            log.debug("[normalizer] request normalization skipped: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 5;
    }

    // ── Stripping (request copies only; history untouched) ───────────

    /**
     * Remove PDF document blocks nested inside tool-result outputs,
     * regardless of multimodal support (QwenPaw #7636): formatters emit
     * OpenAI {@code file} parts from them, which chat-completions servers
     * reject. Sibling image/audio/video blocks are preserved.
     */
    static Msg stripToolResultDocuments(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock filtered = filterToolResultDocuments(trb);
                if (filtered != trb) {
                    changed = true;
                }
                out.add(filtered);
                continue;
            }
            out.add(block);
        }
        if (!changed) {
            return msg;
        }
        return msg.withContent(out);
    }

    private static ToolResultBlock filterToolResultDocuments(ToolResultBlock trb) {
        List<ContentBlock> output = trb.getOutput();
        if (output == null || output.isEmpty()) {
            return trb;
        }
        List<ContentBlock> filtered = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock block : output) {
            if (isDocumentBlock(block)) {
                changed = true;
                continue;
            }
            filtered.add(block);
        }
        if (!changed) {
            return trb;
        }
        if (filtered.isEmpty()) {
            filtered.add(TextBlock.builder()
                    .text("[PDF document removed - not supported on this request path]")
                    .build());
        }
        return ToolResultBlock.builder()
                .id(trb.getId())
                .name(trb.getName())
                .output(filtered)
                .metadata(trb.getMetadata())
                .state(trb.getState())
                .build();
    }

    /** A block carrying a PDF payload (formatter turns it into a file part). */
    private static boolean isDocumentBlock(ContentBlock block) {
        if (block instanceof DataBlock data) {
            String mt = mediaTypeOf(data.getSource());
            return DOCUMENT_MIME_TYPES.contains(mt);
        }
        return false;
    }

    /**
     * Strip empty TextBlocks from assistant messages (QwenPaw #7409): a
     * reasoning-only turn persists as an empty text block and some
     * providers (Volcengine Ark) reject its replay with 400 — one poisoned
     * turn would otherwise break the rest of the session. Returns null when
     * the message consists solely of empty text blocks (caller drops the
     * whole message); non-assistant messages and messages with any
     * non-empty block pass through with empties stripped only when other
     * content remains.
     */
    static Msg dropEmptyAssistantTextBlocks(Msg msg) {
        if (msg.getRole() != io.agentscope.core.message.MsgRole.ASSISTANT) {
            return msg;
        }
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock tb
                    && (tb.getText() == null || tb.getText().isBlank())) {
                changed = true;
                continue;
            }
            out.add(block);
        }
        if (out.isEmpty()) {
            // Whole message was empty text — caller drops it entirely.
            return changed ? null : msg;
        }
        return changed ? msg.withContent(out) : msg;
    }

    private Msg stripMediaBlocks(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }
        List<ContentBlock> out = new ArrayList<>(content.size());
        boolean changed = false;
        for (ContentBlock block : content) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock filtered = filterToolResult(trb);
                if (filtered != trb) {
                    changed = true;
                }
                out.add(filtered);
                continue;
            }
            if (isMediaBlock(block)) {
                changed = true;
                continue;
            }
            out.add(block);
        }
        if (!changed) {
            return msg;
        }
        if (out.isEmpty()) {
            out.add(TextBlock.builder().text(MEDIA_UNSUPPORTED_PLACEHOLDER).build());
        }
        return msg.withContent(out);
    }

    /** Tool results carry nested media in their output blocks. */
    private ToolResultBlock filterToolResult(ToolResultBlock trb) {
        List<ContentBlock> output = trb.getOutput();
        if (output == null || output.isEmpty()) {
            return trb;
        }
        List<ContentBlock> filtered = new ArrayList<>(output.size());
        boolean changed = false;
        for (ContentBlock block : output) {
            if (isMediaBlock(block)) {
                changed = true;
                continue;
            }
            filtered.add(block);
        }
        if (!changed) {
            return trb;
        }
        if (filtered.isEmpty()) {
            filtered.add(TextBlock.builder().text(MEDIA_UNSUPPORTED_PLACEHOLDER).build());
        }
        return ToolResultBlock.builder()
                .id(trb.getId())
                .name(trb.getName())
                .output(filtered)
                .metadata(trb.getMetadata())
                .state(trb.getState())
                .build();
    }

    // ── Media detection (incl. application/pdf, #7621) ───────────────

    private static boolean isMediaBlock(ContentBlock block) {
        if (block instanceof ImageBlock || block instanceof AudioBlock || block instanceof VideoBlock) {
            return true;
        }
        if (block instanceof DataBlock data) {
            String mt = mediaTypeOf(data.getSource());
            return mt.startsWith("image/") || mt.startsWith("audio/")
                    || mt.startsWith("video/") || DOCUMENT_MIME_TYPES.contains(mt);
        }
        return false;
    }

    private static String mediaTypeOf(io.agentscope.core.message.Source source) {
        if (source instanceof Base64Source b64) {
            String mt = b64.getMediaType();
            return mt == null ? "" : mt.toLowerCase();
        }
        if (source instanceof URLSource url) {
            String mt = url.getMimeType();
            return mt == null ? "" : mt.toLowerCase();
        }
        return "";
    }

    // ── Capability lookup ────────────────────────────────────────────

    private boolean supportsMultimodal(String agentId) {
        var slot = modelRoutingService.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return true; // routing unknown → fail open
        }
        var entity = providerModelRepo.findByProviderId(slot.providerId())
                .stream()
                .filter(row -> slot.modelId().equals(row.modelId()))
                .findFirst().orElse(null);
        if (entity != null) {
            return Boolean.TRUE.equals(entity.supportsMultimodal())
                    || Boolean.TRUE.equals(entity.supportsImage())
                    || Boolean.TRUE.equals(entity.supportsVideo());
        }
        // No metadata row — fall back to the same model-id heuristic as
        // /probe-multimodal (models seeded without discovery).
        return heuristicMultimodal(slot.modelId());
    }

    /** Resolve the provider-resolved context window for pressure thresholds. */
    private Integer resolveContextWindow(String agentId) {
        var slot = modelRoutingService.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return null;
        }
        return modelRoutingService.getContextSize(slot.providerId(), slot.modelId());
    }

    private static boolean heuristicMultimodal(String modelId) {
        String lower = modelId == null ? "" : modelId.toLowerCase();
        return lower.contains("vision") || lower.contains("vl") || lower.contains("omni")
                || lower.contains("gpt-4o") || lower.contains("gemini");
    }

}
