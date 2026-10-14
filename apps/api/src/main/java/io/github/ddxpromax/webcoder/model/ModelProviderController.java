package io.github.ddxpromax.webcoder.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelProviderController {
    private final ModelRoutingService routing;

    public ModelProviderController(ModelRoutingService routing) {
        this.routing = routing;
    }

    @GetMapping("/models/active")
    public ResponseEntity<?> getActiveModels(
        @RequestParam(defaultValue = "effective") String scope,
        @RequestParam(name = "agent_id", required = false) String agentId
    ) {
        if ("agent".equals(scope)
            && (agentId == null || agentId.isBlank())) {
            return ResponseEntity.badRequest().body(
                Map.of(
                    "detail",
                    "agent_id is required when scope is 'agent'"
                )
            );
        }

        ModelRoutingService.ModelSlot slot = switch (scope) {
            case "global" -> routing.resolveGlobalModel();
            case "agent" -> routing.resolveAgentModel(agentId);
            default -> routing.resolveEffectiveModel(agentId);
        };

        return ResponseEntity.ok(activeInfo(slot));
    }

    @PutMapping("/models/active")
    public ResponseEntity<?> setActiveModel(
        @RequestBody Map<String, Object> body
    ) {
        String providerId = Objects.toString(
            body.get("provider_id"), ""
        );
        String modelId = Objects.toString(body.get("model"), "");
        String scope = Objects.toString(body.get("scope"), "global");

        if (scope.isBlank()) {
            return ResponseEntity.badRequest().body(
                Map.of("detail", "scope is required")
            );
        }

        // Match Majo's current clear behavior: an incomplete slot
        // clears the global selection.
        if (providerId.isBlank() || modelId.isBlank()) {
            routing.setGlobalActiveModel("", "");
            return ResponseEntity.ok(activeInfo(null));
        }

        if (!routing.hasModel(providerId, modelId)) {
            if (routing.resolveProviderConnection(providerId)
                .baseUrl().isBlank()) {
                return ResponseEntity.status(404).body(
                    Map.of(
                        "detail",
                        "Provider '" + providerId + "' not found."
                    )
                );
            }

            return ResponseEntity.badRequest().body(
                Map.of(
                    "detail",
                    "Model '" + modelId
                        + "' not found in provider '"
                        + providerId + "'."
                )
            );
        }

        if ("agent".equals(scope)) {
            String agentId = Objects.toString(
                body.get("agent_id"), null
            );
            if (agentId == null || agentId.isBlank()) {
                return ResponseEntity.badRequest().body(
                    Map.of(
                        "detail",
                        "agent_id is required when scope is 'agent'"
                    )
                );
            }

            routing.setAgentActiveModel(
                agentId,
                providerId,
                modelId
            );
            return ResponseEntity.ok(
                activeInfo(routing.resolveAgentModel(agentId))
            );
        }

        routing.setGlobalActiveModel(providerId, modelId);
        return ResponseEntity.ok(
            activeInfo(routing.resolveGlobalModel())
        );
    }

    private Map<String, Object> activeInfo(
        ModelRoutingService.ModelSlot slot
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (slot == null || !slot.hasBoth()) {
            result.put("active_llm", null);
            result.put("effective_max_input_length", null);
            return result;
        }

        Map<String, String> activeLlm = new LinkedHashMap<>();
        activeLlm.put("provider_id", slot.providerId());
        activeLlm.put("model", slot.modelId());

        result.put("active_llm", activeLlm);
        result.put(
            "effective_max_input_length",
            routing.getContextSize(
                slot.providerId(),
                slot.modelId()
            )
        );
        return result;
    }
}