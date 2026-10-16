package io.github.ddxpromax.webcoder.compat;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.ddxpromax.webcoder.cron.CronService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class RootCompatControllerTests {
    @Test void exposesMajoVoiceUnavailableResponse(){RootCompatController controller=new RootCompatController(mock(CronService.class));assertThat(controller.voiceIncoming().getBody()).contains("Voice channel is not available.");assertThat(controller.voiceStatus()).containsEntry("status","ignored");}
}
