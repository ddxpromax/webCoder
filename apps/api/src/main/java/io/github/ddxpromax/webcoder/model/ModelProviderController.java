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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelProviderController {
    private final ModelRoutingService routing;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ModelConfigRepository modelConfigRepo;
    private final ObjectMapper mapper;
    private final ModelDiscoveryService discoveryService;

    public ModelProviderController(
        ModelRoutingService routing,
        ProviderRepository providerRepo,
        ProviderModelRepository providerModelRepo,
        ModelConfigRepository modelConfigRepo,
        ObjectMapper mapper,
        ModelDiscoveryService discoveryService
    ) {
        this.routing = routing;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
        this.modelConfigRepo = modelConfigRepo;
        this.mapper = mapper;
        this.discoveryService = discoveryService;
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

    @PutMapping("/models/{provider_id}/config")
    public ResponseEntity<?> configureProvider(
        @PathVariable("provider_id") String providerId,
        @RequestBody Map<String, Object> body
    ) {
        if (providerRepo.findFullById(providerId).isEmpty()) {
            return ResponseEntity.status(404).body(
                Map.of("error", "not found")
            );
        }

        providerRepo.updateConfig(
            providerId,
            new ProviderRepository.ProviderConfigUpdate(
                body.containsKey("api_key"),
                Objects.toString(body.get("api_key"), ""),
                body.containsKey("base_url"),
                Objects.toString(body.get("base_url"), ""),
                body.containsKey("chat_model"),
                Objects.toString(
                    body.get("chat_model"),
                    "OpenAIChatModel"
                )
            )
        );

        return ResponseEntity.ok(
            toProviderDto(
                providerRepo.findFullById(providerId).orElseThrow()
            )
        );
    }

    @PostMapping("/models/custom-providers")
    public ProviderInfoDto createProvider(
        @RequestBody Map<String, String> body
    ) {
        ModelConfigRepository.CustomProviderRow created =
            modelConfigRepo.create(
                body.getOrDefault("name", "Custom"),
                firstNonBlank(body, "api_key", "apiKey", ""),
                firstNonBlank(
                    body,
                    "default_base_url",
                    "baseUrl",
                    "https://api.openai.com/v1"
                ),
                firstNonBlank(
                    body,
                    "chat_model",
                    "modelName",
                    "gpt-4o-mini"
                )
            );

        return toCustomProviderDto(created);
    }

    @DeleteMapping("/models/custom-providers/{provider_id}")
    public List<ProviderInfoDto> deleteProvider(
        @PathVariable("provider_id") String providerId
    ) {
        try {
            modelConfigRepo.deleteById(
                Long.parseLong(providerId)
            );
        } catch (NumberFormatException ignored) {
            // Majo leaves the catalog unchanged for a nonnumeric id.
        }

        return listProviders();
    }

    private static String firstNonBlank(
        Map<String, String> body,
        String key,
        String fallback
    ) {
        String value = body.get(key);
        return value == null || value.isBlank()
            ? fallback
            : value.trim();
    }

    private static String firstNonBlank(
        Map<String, String> body,
        String firstKey,
        String secondKey,
        String fallback
    ) {
        String value = body.get(firstKey);
        if (value == null || value.isBlank()) {
            value = body.get(secondKey);
        }
        return value == null || value.isBlank()
            ? fallback
            : value.trim();
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

    @PutMapping("/models/{provider_id}/models/{model_id}/visibility")
    public ResponseEntity<?> setModelVisibility(
        @PathVariable("provider_id") String providerId,
        @PathVariable("model_id") String modelId,
        @RequestBody Map<String, Object> body
    ) {
        if (providerModelRepo.findByProviderIdAndModelId(
            providerId, modelId
        ).isEmpty()) {
            return missingModel(providerId, modelId);
        }

        boolean hidden = Boolean.TRUE.equals(body.get("hidden"));
        providerModelRepo.setHidden(providerId, modelId, hidden);

        var provider = providerRepo.findFullById(providerId);
        if (provider.isPresent()) {
            return ResponseEntity.ok(
                toProviderDto(provider.get())
            );
        }
        return ResponseEntity.ok(Map.of("hidden", hidden));
    }

    @PostMapping("/models/{provider_id}/models")
    public Object addModel(
        @PathVariable("provider_id") String providerId,
        @RequestBody Map<String, String> body
    ) {
        try {
            long id = Long.parseLong(providerId);
            var custom = modelConfigRepo.findFullById(id);

            if (custom.isPresent()) {
                String modelName = body.getOrDefault(
                    "model_id",
                    body.getOrDefault(
                        "modelName",
                        custom.get().modelName()
                    )
                );
                modelConfigRepo.updateModelName(id, modelName);
                return toCustomProviderDto(
                    modelConfigRepo.findFullById(id).orElseThrow()
                );
            }
        } catch (NumberFormatException ignored) {
            // Majo returns {} for a nonnumeric provider id.
        }

        return Map.of();
    }

    @DeleteMapping("/models/{provider_id}/models/{model_id}")
    public Object removeModel(
        @PathVariable("provider_id") String providerId,
        @PathVariable("model_id") String modelId
    ) {
        try {
            long id = Long.parseLong(providerId);
            if (modelConfigRepo.findFullById(id).isPresent()) {
                // Majo resets the single custom model regardless of modelId.
                modelConfigRepo.updateModelName(id, "gpt-4o-mini");
                return toCustomProviderDto(
                    modelConfigRepo.findFullById(id).orElseThrow()
                );
            }
        } catch (NumberFormatException ignored) {
            // Majo returns {} for a nonnumeric provider id.
        }

        return Map.of();
    }

    @PutMapping("/models/{provider_id}/models/{model_id}/config")
    public ResponseEntity<?> configureModel(
        @PathVariable("provider_id") String providerId,
        @PathVariable("model_id") String modelId,
        @RequestBody Map<String, Object> body
    ) {
        // Majo checks numeric custom-provider ids before built-in models.
        try {
            long id = Long.parseLong(providerId);
            var custom = modelConfigRepo.findFullById(id);

            if (custom.isPresent()) {
                if (body.containsKey("model_name")
                    || body.containsKey("model_id")) {
                    String modelName = Objects.toString(
                        body.getOrDefault(
                            "model_name",
                            body.get("model_id")
                        ),
                        custom.get().modelName()
                    );
                    modelConfigRepo.updateModelName(id, modelName);
                }

                return ResponseEntity.ok(
                    toCustomProviderDto(
                        modelConfigRepo.findFullById(id).orElseThrow()
                    )
                );
            }
        } catch (NumberFormatException ignored) {
            // Continue with the built-in provider model lookup.
        }

        if (providerModelRepo.findByProviderIdAndModelId(
            providerId, modelId
        ).isEmpty()) {
            return missingModel(providerId, modelId);
        }

        Map<String, Object> updates = new LinkedHashMap<>();

        if (body.containsKey("max_tokens")) {
            updates.put(
                "max_tokens",
                ((Number) body.get("max_tokens")).intValue()
            );
        }
        if (body.containsKey("max_input_length")) {
            updates.put(
                "max_input_length",
                ((Number) body.get("max_input_length")).intValue()
            );
        }
        if (body.containsKey("generate_kwargs")) {
            updates.put(
                "generate_kwargs",
                writeJson(body.get("generate_kwargs"))
            );
        }
        if (body.containsKey("relay_reasoning")) {
            updates.put(
                "relay_reasoning",
                Boolean.TRUE.equals(body.get("relay_reasoning"))
            );
        }
        if (body.containsKey("thinking_enabled")) {
            Object value = body.get("thinking_enabled");
            updates.put(
                "thinking_enabled",
                value == null ? null : Boolean.TRUE.equals(value)
            );
        }
        if (body.containsKey("thinking_budget")) {
            Object value = body.get("thinking_budget");
            updates.put(
                "thinking_budget",
                value == null ? null : ((Number) value).intValue()
            );
        }
        if (body.containsKey("reasoning_effort")) {
            Object value = body.get("reasoning_effort");
            updates.put(
                "reasoning_effort",
                value == null ? null : String.valueOf(value)
            );
        }

        providerModelRepo.updateConfig(providerId, modelId, updates);

        var provider = providerRepo.findFullById(providerId);
        if (provider.isPresent()) {
            return ResponseEntity.ok(
                toProviderDto(provider.get())
            );
        }
        return ResponseEntity.ok(Map.of());
    }

    private ResponseEntity<?> missingModel(
        String providerId,
        String modelId
    ) {
        return ResponseEntity.status(404).body(Map.of(
            "detail",
            "Model '" + modelId
                + "' not found in provider '"
                + providerId + "'"
        ));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> resolveConnectionParams(
        String providerId,
        Map<String, Object> body
    ) {
        var connection = routing.resolveProviderConnection(providerId);
        String baseUrl = connection.baseUrl();
        String apiKey = connection.apiKey();
        if (body != null) {
            if (body.get("base_url") != null) {
                baseUrl = String.valueOf(body.get("base_url"));
            }
            if (body.get("api_key") != null) {
                apiKey = String.valueOf(body.get("api_key"));
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("base_url", baseUrl);
        result.put("api_key", apiKey);
        return result;
    }

    @PostMapping("/models/{provider_id}/test")
    public Map<String, Object> testProvider(
        @PathVariable("provider_id") String providerId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        var params = resolveConnectionParams(providerId, body);
        boolean success = discoveryService.testConnection(
            params.get("base_url"),
            params.get("api_key")
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put(
            "message",
            success
                ? "Connection successful"
                : "Connection failed: invalid base_url or api_key"
        );
        return result;
    }

    @PostMapping("/models/{provider_id}/models/test")
    public Map<String, Object> testModel(
        @PathVariable("provider_id") String providerId,
        @RequestBody Map<String, Object> body
    ) {
        var params = resolveConnectionParams(providerId, body);
        String modelId = body.get("model_id") == null
            ? ""
            : String.valueOf(body.get("model_id"));
        boolean success = discoveryService.testConnection(
            params.get("base_url"),
            params.get("api_key")
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put(
            "message",
            success
                ? "Model '" + modelId + "' connection successful"
                : "Model connection failed: invalid base_url or api_key"
        );
        return result;
    }

    @PostMapping("/models/{provider_id}/discover")
    public Map<String, Object> discoverModels(
        @PathVariable("provider_id") String providerId,
        @RequestBody(required = false) Map<String, Object> body,
        @RequestParam(defaultValue = "true") boolean save
    ) {
        var params = resolveConnectionParams(providerId, body);
        var discovery = discoveryService.discover(
            providerId,
            params.get("base_url"),
            params.get("api_key"),
            save
        );
        List<ModelInfoDto> resultModels = new ArrayList<>();
        for (var discovered : discovery.models()) {
            ModelInfoDto dto = new ModelInfoDto();
            dto.setId(discovered.id());
            dto.setName(discovered.name());
            dto.setProbeSource(discovered.probeSource());
            dto.setMaxTokens(discovered.maxTokens());
            dto.setMaxInputLength(discovered.maxInputLength());
            dto.setMaxInputLengthConfigured(
                discovered.maxInputLengthConfigured()
            );
            resultModels.add(dto);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", discovery.success());
        result.put("message", discovery.message());
        result.put("models", resultModels);
        result.put(
            "added_count",
            save && discovery.success() ? resultModels.size() : 0
        );
        return result;
    }

    @PostMapping("/models/{provider_id}/models/{model_id}/probe-multimodal")
    public Map<String, Object> probeMultimodal(
        @PathVariable("provider_id") String providerId,
        @PathVariable("model_id") String modelId
    ) {
        String lower = modelId.toLowerCase();
        boolean supportsImage = lower.contains("vision")
            || lower.contains("vl")
            || lower.contains("omni")
            || lower.contains("gpt-4o")
            || lower.contains("gemini");
        boolean supportsVideo = lower.contains("video")
            || lower.contains("gemini");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supports_image", supportsImage);
        result.put("supports_video", supportsVideo);
        result.put(
            "supports_multimodal",
            supportsImage || supportsVideo
        );
        result.put(
            "image_message",
            supportsImage ? "" : "Model does not support image input"
        );
        result.put(
            "video_message",
            supportsVideo ? "" : "Model does not support video input"
        );
        return result;
    }
}
