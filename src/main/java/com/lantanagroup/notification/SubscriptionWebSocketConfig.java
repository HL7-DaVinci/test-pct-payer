package com.lantanagroup.notification;

import ca.uhn.fhir.jpa.model.entity.StorageSettings;
import ca.uhn.fhir.jpa.subscription.match.deliver.websocket.SubscriptionWebsocketHandler;
import ca.uhn.fhir.jpa.subscription.match.deliver.websocket.WebsocketConnectionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.PerConnectionWebSocketHandler;

@Configuration
@EnableWebSocket
public class SubscriptionWebSocketConfig implements WebSocketConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionWebSocketConfig.class);

    private final StorageSettings storageSettings;

    public SubscriptionWebSocketConfig(StorageSettings storageSettings) {
        this.storageSettings = storageSettings;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String websocketPath = storageSettings.getWebsocketContextPath();
        logger.info("Registering WebSocket handler at path={}", websocketPath);
        registry.addHandler(subscriptionWebSocketHandler(), websocketPath)
                .setAllowedOrigins("*");
    }

    @Bean
    public WebsocketConnectionValidator websocketConnectionValidator() {
        return new WebsocketConnectionValidator();
    }

    @Bean
    public WebSocketHandler subscriptionWebSocketHandler() {
        return new PerConnectionWebSocketHandler(SubscriptionWebsocketHandler.class);
    }
}