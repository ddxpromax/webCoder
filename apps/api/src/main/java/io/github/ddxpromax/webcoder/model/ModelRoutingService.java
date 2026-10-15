package io.github.ddxpromax.webcoder.model;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelRoutingService {
    private final ActiveModelRepository activeModels;
    private final ProviderRepository providers;
    private final ProviderModelRepository providerModels;
    private final ModelConfigRepository modelConfigs;

    public ModelRoutingService(
        ActiveModelRepository activeModels,
        ProviderRepository providers,
        ProviderModelRepository providerModels,
        ModelConfigRepository modelConfigs
    ) {
        this.activeModels = activeModels;
        this.providers = providers;
        this.providerModels = providerModels;
        this.modelConfigs = modelConfigs;
    }

    public ModelSlot resolveGlobalModel() {
        return activeModels.findGlobal()
            .map(row -> new ModelSlot(
                row.providerId(),
                row.modelId()
            ))
            .orElse(null);
    }

    public ModelSlot resolveAgentModel(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }

        return activeModels.findByScopeAndAgentId("agent", agentId)
            .map(row -> new ModelSlot(
                row.providerId(),
                row.modelId()
            ))
            .filter(ModelSlot::hasBoth)
            .orElse(null);
    }

    public ModelSlot resolveEffectiveModel(String agentId) {
        ModelSlot agent = resolveAgentModel(agentId);
        if (agent != null && agent.hasBoth()) {
            return agent;
        }

        ModelSlot global = resolveGlobalModel();
        return global != null && global.hasBoth()
            ? global
            : ModelSlot.empty();
    }

    public boolean hasModel(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return false;
        }

        if (providers.findById(providerId).isPresent()) {
            return providerModels.findByProviderIdAndModelId(
                providerId,
                modelId
            ).isPresent();
        }

        Long customId = parseCustomId(providerId);
        return customId != null && modelConfigs.findById(customId)
            .map(config -> modelId.equals(config.modelName()))
            .orElse(false);
    }

    public Integer getContextSize(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return null;
        }

        if (providers.findById(providerId).isPresent()) {
            return providerModels.findByProviderIdAndModelId(
                providerId,
                modelId
            )
                .map(ProviderModelRepository.ModelMetadata::maxInputLength)
                .filter(length -> length > 0)
                .orElse(null);
        }

        Long customId = parseCustomId(providerId);
        if (customId != null && modelConfigs.findById(customId).isPresent()) {
            return 128000;
        }
        return null;
    }

    public ProviderConnection resolveProviderConnection(
        String providerId
    ) {
        if (providerId == null || providerId.isBlank()) {
            return new ProviderConnection("", "", "OpenAIChatModel");
        }

        var builtIn = providers.findById(providerId);
        if (builtIn.isPresent()) {
            var provider = builtIn.get();
            return new ProviderConnection(
                provider.baseUrl(),
                provider.apiKey(),
                provider.chatModel() == null
                    ? "OpenAIChatModel"
                    : provider.chatModel()
            );
        }

        Long customId = parseCustomId(providerId);
        if (customId != null) {
            var custom = modelConfigs.findById(customId);
            if (custom.isPresent()) {
                return new ProviderConnection(
                    custom.get().baseUrl(),
                    custom.get().apiKey(),
                    "OpenAIChatModel"
                );
            }
        }

        return new ProviderConnection("", "", "OpenAIChatModel");
    }

    public OpenAIChatModel buildOpenAIChatModel(
        String providerId,
        String modelId
    ) {
        ProviderConnection connection = resolveProviderConnection(
            providerId
        );
        String resolvedModel = modelId == null
            ? "gpt-4o-mini"
            : modelId;
        if (connection.baseUrl().isBlank()) {
            return OpenAIChatModel.builder()
                .apiKey(connection.apiKey())
                .baseUrl("https://api.openai.com/v1")
                .modelName(resolvedModel)
                .build();
        }

        var builder = OpenAIChatModel.builder()
            .apiKey(connection.apiKey())
            .baseUrl(connection.baseUrl())
            .modelName(resolvedModel);
        Integer contextSize = getContextSize(providerId, modelId);
        if (contextSize != null && contextSize > 0) {
            builder.contextWindowSize(contextSize);
        }
        return builder.build();
    }

    @Transactional
    public void setGlobalActiveModel(
        String providerId,
        String modelId
    ) {
        activeModels.setGlobal(
            providerId == null ? "" : providerId,
            modelId == null ? "" : modelId
        );
    }

    @Transactional
    public void setAgentActiveModel(
        String agentId,
        String providerId,
        String modelId
    ) {
        if (agentId == null || agentId.isBlank()) {
            setGlobalActiveModel(providerId, modelId);
            return;
        }

        activeModels.setAgent(
            agentId,
            providerId == null ? "" : providerId,
            modelId == null ? "" : modelId
        );
    }

    private static Long parseCustomId(String providerId) {
        try {
            return Long.parseLong(providerId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record ModelSlot(String providerId, String modelId) {
        public static ModelSlot empty() {
            return new ModelSlot("", "");
        }

        public boolean hasBoth() {
            return providerId != null && !providerId.isBlank()
                && modelId != null && !modelId.isBlank();
        }
    }

    public record ProviderConnection(
        String baseUrl,
        String apiKey,
        String chatModel
    ) {}
}
