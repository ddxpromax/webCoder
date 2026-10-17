package io.github.ddxpromax.webcoder.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import org.junit.jupiter.api.Test;

class SkillOptimizationServiceTests {
    @Test
    void reportsAnEnglishConfigurationErrorBeforeOpeningAModelStream() {
        ModelRoutingService models = mock(ModelRoutingService.class);
        when(models.resolveEffectiveModel("default"))
            .thenReturn(ModelRoutingService.ModelSlot.empty());

        var service = new SkillOptimizationService(models, new ObjectMapper());

        assertThat(service.stream("---\nname: review\n---", "default")
            .collectList().block())
            .containsExactly("{\"error\":\"No AI model configured. Please configure a model in Settings.\"}");
    }
}
