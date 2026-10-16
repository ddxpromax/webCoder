package io.github.ddxpromax.webcoder.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class DesktopControllerTests {
    @Test void refusesShutdownWhenNotStartedByDesktopShell(){DesktopController controller=new DesktopController(mock(ApplicationContext.class));if(System.getenv(DesktopController.TOKEN_ENV)==null||System.getenv(DesktopController.TOKEN_ENV).isBlank())assertThat(controller.shutdown("anything").getStatusCode().value()).isEqualTo(403);}
}
