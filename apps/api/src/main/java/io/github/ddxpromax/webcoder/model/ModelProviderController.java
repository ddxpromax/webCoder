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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.dto.ModelInfoDto;
import io.github.ddxpromax.webcoder.dto.ProviderInfoDto;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelProviderController {
    private final ModelRoutingService routing;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ModelConfigRepository modelConfigRepo;
    private final ObjectMapper mapper;

    public ModelProviderController(
        ModelRoutingService routing,
        ProviderRepository providerRepo,
        ProviderModelRepository providerModelRepo,
        ModelConfigRepository modelConfigRepo,
        ObjectMapper mapper
    ) {
        this.routing = routing;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
        this.modelConfigRepo = modelConfigRepo;
        this.mapper = mapper;
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

    @GetMapping("/models")
    public List<ProviderInfoDto> listProviders() {
        List<ProviderInfoDto> result = new ArrayList<>();

        for (ProviderRepository.ProviderRow provider
            : providerRepo.findAllByOrderByNameAsc()) {
            result.add(toProviderDto(provider));
        }

        for (ModelConfigRepository.CustomProviderRow custom
            : modelConfigRepo.findAll()) {
            result.add(toCustomProviderDto(custom));
        }

        return result;
    }

    private ProviderInfoDto toProviderDto(
        ProviderRepository.ProviderRow provider
    ) {
        ProviderInfoDto dto = new ProviderInfoDto();

        dto.setId(provider.id());
        dto.setName(provider.name());
        dto.setBaseUrl(provider.baseUrl());
        dto.setApiKey(provider.apiKey());
        dto.setChatModel(provider.chatModel());
        dto.setModels(toModelDtoList(
            providerModelRepo.findByProviderId(provider.id())
        ));
        dto.setExtraModels(List.of());

        dto.setApiKeyPrefix(provider.apiKeyPrefix());
        dto.setApiKeyPrefixes(parseJson(provider.apiKeyPrefixes()));
        dto.setLocal(provider.isLocal());
        dto.setFreezeUrl(provider.freezeUrl());
        dto.setRequireApiKey(provider.requireApiKey());
        dto.setCustom(provider.isCustom());
        dto.setSupportModelDiscovery(
            provider.supportModelDiscovery()
        );
        dto.setSupportConnectionCheck(
            provider.supportConnectionCheck()
        );

        dto.setGenerateKwargs(parseJson(provider.generateKwargs()));
        dto.setCustomHeaders(parseJson(provider.customHeaders()));
        dto.setAuthMode(provider.authMode());
        dto.setSupportsOauth(provider.supportsOauth());
        dto.setOauthConnected(provider.oauthConnected());
        dto.setFreeTier(provider.isFreeTier());
        dto.setProviderGroup(provider.providerGroup());
        dto.setProviderGroupName(provider.providerGroupName());
        dto.setProviderVariant(provider.providerVariant());
        dto.setThinkingParamStyle(provider.thinkingParamStyle());
        dto.setReasoningEffortOptions(
            parseJson(provider.reasoningEffortOptions())
        );
        dto.setThinkingBudgetRange(
            parseJson(provider.thinkingBudgetRange())
        );
        dto.setMeta(parseJson(provider.extraFields()));

        return dto;
    }

    private List<ModelInfoDto> toModelDtoList(
        List<ProviderModelRepository.ModelRow> models
    ) {
        List<ModelInfoDto> result = new ArrayList<>();

        for (ProviderModelRepository.ModelRow model : models) {
            ModelInfoDto dto = new ModelInfoDto();

            dto.setId(model.modelId());
            dto.setName(model.name());
            dto.setSupportsMultimodal(model.supportsMultimodal());
            dto.setSupportsImage(model.supportsImage());
            dto.setSupportsVideo(model.supportsVideo());
            dto.setProbeSource(model.probeSource());
            dto.setIsFree(model.isFree());
            dto.setMaxTokens(model.maxTokens());
            dto.setMaxInputLength(model.maxInputLength());
            dto.setMaxInputLengthConfigured(
                model.maxInputLengthConfigured()
            );
            dto.setGenerateKwargs(parseJson(model.generateKwargs()));
            dto.setRelayReasoning(model.relayReasoning());
            dto.setThinkingEnabled(model.thinkingEnabled());
            dto.setThinkingBudget(model.thinkingBudget());
            dto.setReasoningEffort(model.reasoningEffort());
            dto.setThinkingParamStyle(model.thinkingParamStyle());
            dto.setReasoningEffortOptions(
                parseJson(model.reasoningEffortOptions())
            );
            dto.setThinkingBudgetRange(
                parseJson(model.thinkingBudgetRange())
            );
            dto.setHidden(model.hidden());

            result.add(dto);
        }

        return result;
    }

    private ProviderInfoDto toCustomProviderDto(
        ModelConfigRepository.CustomProviderRow custom
    ) {
        ProviderInfoDto dto = new ProviderInfoDto();

        dto.setId(custom.id().toString());
        dto.setName(custom.name());
        dto.setBaseUrl(custom.baseUrl());
        dto.setApiKey(custom.apiKey().isEmpty() ? "" : "****");
        dto.setChatModel("OpenAIChatModel");

        ModelInfoDto model = new ModelInfoDto();
        model.setId(custom.modelName());
        model.setName(custom.modelName());
        model.setSupportsMultimodal(false);
        model.setSupportsImage(false);
        model.setSupportsVideo(false);
        model.setProbeSource("custom");
        model.setIsFree(false);
        model.setMaxTokens(128000);
        model.setMaxInputLength(128000);
        model.setMaxInputLengthConfigured(false);
        model.setGenerateKwargs(Map.of());
        model.setRelayReasoning(true);

        dto.setModels(List.of(model));
        dto.setExtraModels(List.of());
        dto.setApiKeyPrefix(
            custom.apiKey().isEmpty()
                ? ""
                : custom.apiKey().substring(
                    0,
                    Math.min(4, custom.apiKey().length())
                ) + "***"
        );
        dto.setApiKeyPrefixes(List.of());
        dto.setLocal(false);
        dto.setFreezeUrl(false);
        dto.setRequireApiKey(true);
        dto.setCustom(true);
        dto.setSupportModelDiscovery(false);
        dto.setSupportConnectionCheck(true);
        dto.setGenerateKwargs(Map.of());
        dto.setCustomHeaders(Map.of());
        dto.setAuthMode("api_key");
        dto.setSupportsOauth(false);
        dto.setOauthConnected(false);
        dto.setFreeTier(false);
        dto.setProviderGroup("");
        dto.setProviderGroupName("");
        dto.setProviderVariant("");
        dto.setReasoningEffortOptions(
            List.of("none", "minimal", "low", "medium", "high", "xhigh")
        );
        dto.setThinkingBudgetRange(List.of(1, 81920));
        dto.setMeta(Map.of());

        return dto;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return mapper.readValue(
                json,
                new TypeReference<Object>() {}
            );
        } catch (Exception exception) {
            return json;
        }
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