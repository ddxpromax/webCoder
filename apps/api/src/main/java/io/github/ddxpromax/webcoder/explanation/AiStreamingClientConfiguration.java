package io.github.ddxpromax.webcoder.explanation;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;


@Configuration(proxyBeanMethods = false)
public class AiStreamingClientConfiguration {

    @Bean
    WebClient aiStreamingClient(
        WebClient.Builder builder,
        @Value("${webcoder.ai.base-url}") String baseUrl
    ) {
        ClientHttpConnector connector = ClientHttpConnectorBuilder.detect()
            .build(
                HttpClientSettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(2))
                    .withReadTimeout(null)
            );

        return builder.clone()
            .baseUrl(baseUrl)
            .clientConnector(connector)
            .build();
    }
}
