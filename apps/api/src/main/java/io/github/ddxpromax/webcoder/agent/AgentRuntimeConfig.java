package io.github.ddxpromax.webcoder.agent;

import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRuntimeConfig {
    @Bean
    Toolkit toolkit() {
        return new Toolkit();
    }
}
