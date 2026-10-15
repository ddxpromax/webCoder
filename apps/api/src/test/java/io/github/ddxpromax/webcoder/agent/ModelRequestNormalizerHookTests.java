package io.github.ddxpromax.webcoder.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.github.ddxpromax.webcoder.model.ModelRoutingService;
import io.github.ddxpromax.webcoder.model.ProviderModelRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelRequestNormalizerHookTests {
    @Test
    void textOnlyModelStripsImageFromOutgoingRequest() {
        ModelRoutingService routing = mock(ModelRoutingService.class);
        ProviderModelRepository models = mock(
            ProviderModelRepository.class
        );
        when(routing.resolveEffectiveModel("default")).thenReturn(
            new ModelRoutingService.ModelSlot(
                "provider", "text-only"
            )
        );
        when(models.findByProviderId("provider"))
            .thenReturn(List.of());
        Msg original = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .content(List.of(
                TextBlock.builder().text("What is shown?").build(),
                ImageBlock.builder().source(
                    URLSource.builder()
                        .url("file:///tmp/example.png")
                        .mimeType("image/png")
                        .build()
                ).build()
            ))
            .build();
        PreReasoningEvent event = mock(PreReasoningEvent.class);
        when(event.getInputMessages()).thenReturn(List.of(original));

        new ModelRequestNormalizerHook(
            "default", routing, models
        ).onEvent(event).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages =
            ArgumentCaptor.forClass(List.class);
        verify(event).setInputMessages(messages.capture());
        assertThat(messages.getValue().getFirst().getContent())
            .hasSize(1)
            .first().isInstanceOf(TextBlock.class);
        assertThat(original.getContent()).hasSize(2);
    }
}
