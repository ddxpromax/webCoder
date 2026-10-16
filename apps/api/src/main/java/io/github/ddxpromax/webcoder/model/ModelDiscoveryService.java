package io.github.ddxpromax.webcoder.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelDiscoveryService {
    private static final Logger log =
        LoggerFactory.getLogger(ModelDiscoveryService.class);
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ProviderModelRepository models;
    private final ObjectMapper mapper;

    public ModelDiscoveryService(
        ProviderModelRepository models,
        ObjectMapper mapper
    ) {
        this.models = models;
        this.mapper = mapper;
    }

    public record DiscoveredModel(
        String id,
        String name,
        String probeSource,
        Integer maxTokens,
        Integer maxInputLength,
        Boolean maxInputLengthConfigured
    ) {}

    public record DiscoveryResult(
        List<DiscoveredModel> models,
        boolean success,
        String message
    ) {}

    public DiscoveryResult discover(
        String providerId,
        String baseUrl,
        String apiKey,
        boolean save
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new DiscoveryResult(
                List.of(), false, "base_url is required"
            );
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(
                URI.create(modelsUrl(baseUrl))
            )
                .timeout(Duration.ofSeconds(30))
                .header(
                    "Authorization",
                    "Bearer " + (apiKey == null ? "" : apiKey)
                )
                .header("Content-Type", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                return new DiscoveryResult(
                    List.of(), false,
                    "Models request failed with HTTP "
                        + response.statusCode()
                );
            }

            JsonNode root = mapper.readTree(response.body());
            List<DiscoveredModel> discovered = new ArrayList<>();
            for (JsonNode node : root.path("data")) {
                String modelId = node.path("id").asText("");
                if (modelId.isBlank()) {
                    continue;
                }

                String name = node.path("name").asText(modelId);
                var existing = models.findDiscoveryRow(
                    providerId, modelId
                );
                Integer maxTokens = existing
                    .map(ProviderModelRepository.DiscoveryRow::maxTokens)
                    .orElse(8192);
                Integer maxInputLength = existing
                    .map(ProviderModelRepository.DiscoveryRow::maxInputLength)
                    .orElse(131072);
                Boolean configured = existing
                    .map(ProviderModelRepository.DiscoveryRow::maxInputLengthConfigured)
                    .orElse(false);

                if (save) {
                    models.saveDiscoveredModel(
                        providerId,
                        modelId,
                        name,
                        existing.map(
                            ProviderModelRepository.DiscoveryRow::id
                        ).orElse(null)
                    );
                }
                discovered.add(new DiscoveredModel(
                    modelId,
                    name,
                    "openai-models",
                    maxTokens,
                    maxInputLength,
                    configured
                ));
            }
            return new DiscoveryResult(
                discovered,
                true,
                "Discovered " + discovered.size() + " models"
            );
        } catch (Exception exception) {
            log.warn(
                "Model discovery failed for {}: {}",
                providerId,
                exception.getMessage()
            );
            return new DiscoveryResult(
                List.of(),
                false,
                exception.getMessage() == null
                    ? "discovery failed"
                    : exception.getMessage()
            );
        }
    }

    public boolean testConnection(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                URI.create(modelsUrl(baseUrl))
            )
                .timeout(Duration.ofSeconds(20))
                .header(
                    "Authorization",
                    "Bearer " + (apiKey == null ? "" : apiKey)
                )
                .GET()
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            return response.statusCode() == 200;
        } catch (Exception exception) {
            log.warn(
                "Connection test failed: {}",
                exception.getMessage()
            );
            return false;
        }
    }

    private static String modelsUrl(String baseUrl) {
        return baseUrl.endsWith("/")
            ? baseUrl + "models"
            : baseUrl + "/models";
    }
}
